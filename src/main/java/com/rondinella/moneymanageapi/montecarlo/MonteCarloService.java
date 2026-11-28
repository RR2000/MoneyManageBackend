package com.rondinella.moneymanageapi.montecarlo;

import com.rondinella.moneymanageapi.brokertransactions.BrokerTransaction;
import com.rondinella.moneymanageapi.brokertransactions.BrokerTransactionRepository;
import com.rondinella.moneymanageapi.brokertransactions.BrokerTransactionService;
import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.common.dtos.GraphPointsDto;
import com.rondinella.moneymanageapi.investments.Portfolio;
import com.rondinella.moneymanageapi.plan.Projection;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
public class MonteCarloService {

  private final BrokerTransactionService brokerTransactionService;
  private final BrokerTransactionRepository brokerTransactionRepository;

  public MonteCarloService(BrokerTransactionService brokerTransactionService,
                           BrokerTransactionRepository brokerTransactionRepository) {
    this.brokerTransactionService = brokerTransactionService;
    this.brokerTransactionRepository = brokerTransactionRepository;
  }

  public static final int MAX_YEARS = 50;
  public static final int MIN_SIMULATIONS = 100;
  public static final int MAX_SIMULATIONS = 20_000;

  /**
   * Runs a Monte Carlo simulation projecting portfolio worth into the future.
   *
   * @param years       number of years to project
   * @param simulations number of simulation paths
   * @return GraphPointsDto with percentile curves: P5, P25, P50, P75, P95
   */
  public GraphPointsDto simulate(int years, int simulations) {
    // bounded: the work grows with years x simulations, and anybody with an account can ask
    if (years < 1 || years > MAX_YEARS || simulations < MIN_SIMULATIONS || simulations > MAX_SIMULATIONS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "years: 1-" + MAX_YEARS + ", simulations: "
          + MIN_SIMULATIONS + "-" + MAX_SIMULATIONS);
    }
    // 1. Get historical monthly returns from the broker worth graph
    Timestamp from = Utils.stringToTimestamp("2021-01-01");
    Timestamp to = Utils.todayAsTimestamp();
    GraphPointsDto historical = brokerTransactionService.worthGraph(from, to);

    Map<String, BigDecimal> totalWorth = historical.getGraph("Total Worth");
    if (totalWorth == null || totalWorth.size() < 2) {
      return new GraphPointsDto();
    }
    List<String> dates = new ArrayList<>(totalWorth.keySet());
    List<BigDecimal> totalWorthSeries = new ArrayList<>(totalWorth.values());
    List<BrokerTransaction> transactions = brokerTransactionRepository.findAll();

    // 2. Monthly log-returns of the market, not of the savings: the money put in during the month
    //    (purchases at their price) is taken out of the month's growth
    List<Double> logReturns = new ArrayList<>();
    for (int i = 1; i < totalWorthSeries.size(); i++) {
      BigDecimal prev = totalWorthSeries.get(i - 1);
      BigDecimal curr = totalWorthSeries.get(i).subtract(invested(transactions, dates.get(i - 1), dates.get(i)));
      if (prev.compareTo(BigDecimal.ZERO) > 0 && curr.compareTo(BigDecimal.ZERO) > 0) {
        logReturns.add(Math.log(curr.doubleValue() / prev.doubleValue()));
      }
    }

    if (logReturns.isEmpty()) {
      return new GraphPointsDto();
    }

    double meanReturn = logReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    double stdDev = computeStdDev(logReturns, meanReturn);

    double startValue = totalWorthSeries.get(totalWorthSeries.size() - 1).doubleValue();
    int months = years * 12;

    // 3. Run the simulations month by month, keeping only the current value of each path
    //    (memory: one value per path, not one per path and month)
    Random rng = new Random(42);
    double[] values = new double[simulations];
    Arrays.fill(values, startValue);
    double[] sorted = new double[simulations];

    // 4. Percentiles at each time step
    int[] percentileValues = {5, 25, 50, 75, 95};
    GraphPointsDto result = new GraphPointsDto();

    LocalDate today = LocalDate.now();
    for (int m = 0; m <= months; m++) {
      if (m > 0) {
        for (int s = 0; s < simulations; s++) {
          values[s] *= Math.exp(meanReturn + stdDev * rng.nextGaussian());
        }
      }
      String label = today.plusMonths(m).withDayOfMonth(1).toString();
      System.arraycopy(values, 0, sorted, 0, simulations);
      Arrays.sort(sorted);

      for (int pct : percentileValues) {
        String seriesName = "P" + pct;
        int idx = (int) Math.floor(pct / 100.0 * (simulations - 1));
        result.addPoint(seriesName, label, BigDecimal.valueOf(sorted[idx]).setScale(2, RoundingMode.HALF_UP));
      }
    }

    return result;
  }

  /** Value of the purchases (minus sales) made after {@code from} and up to {@code to} (yyyy-MM-dd). */
  private static BigDecimal invested(List<BrokerTransaction> transactions, String from, String to) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BrokerTransaction t : transactions) {
      String day = t.getDatetime().toLocalDateTime().toLocalDate().toString();
      if (day.compareTo(from) > 0 && day.compareTo(to) <= 0) {
        sum = sum.add(t.getQuantity().multiply(t.getStockPrice()));
      }
    }
    return sum;
  }

  /** simulated paths of a plan (fixed seed: the same plan gives the same band) */
  public static final int PLAN_SIMULATIONS = 2000;
  /** fewer complete months of history than this: no band (a mean and a deviation of 3 months say nothing) */
  public static final int MIN_HISTORY_MONTHS = 6;
  private static final long PLAN_SEED = 42;

  /**
   * The band of a plan: {@code simulations} paths with the payments and events of the plan, month by month like
   * {@link Projection#path}, each month growing by exp(m + s z), z normal: m and s are the mean and the standard
   * deviation of the portfolio's real monthly log returns ({@link #marketReturns}). Percentiles 5, 50 and 95 at every
   * month of the plan (index 0 = the start).
   */
  public record Band(int historyMonths, double monthlyMean, double monthlyStdDev, int simulations, double[] p5,
                     double[] p50, double[] p95) {
  }

  /**
   * The monthly log returns of the market from the investments history (end of every complete month): the money put in
   * during a month (purchases minus sales, {@code invested}) is taken out of that month's growth, so the payments do not
   * look like returns. A month that starts or ends at zero is skipped.
   */
  public static List<Double> marketReturns(List<Portfolio.Point> history, LocalDate today) {
    List<Portfolio.Point> ends = history.stream()
        .filter(p -> p.date().equals(YearMonth.from(p.date()).atEndOfMonth()) && !p.date().isAfter(today))
        .toList();
    List<Double> returns = new ArrayList<>();
    for (int i = 1; i < ends.size(); i++) {
      double before = ends.get(i - 1).value().doubleValue();
      double putIn = ends.get(i).invested().subtract(ends.get(i - 1).invested()).doubleValue();
      double after = ends.get(i).value().doubleValue() - putIn;
      if (before > 0 && after > 0) {
        returns.add(Math.log(after / before));
      }
    }
    return returns;
  }

  /** null when the history has fewer than {@link #MIN_HISTORY_MONTHS} months */
  public static Band band(List<Double> logReturns, Projection.Schedule schedule, int simulations) {
    if (logReturns.size() < MIN_HISTORY_MONTHS) {
      return null;
    }
    double mean = logReturns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    double sd = Math.sqrt(logReturns.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0));
    int n = schedule.months();
    double[] p5 = new double[n + 1];
    double[] p50 = new double[n + 1];
    double[] p95 = new double[n + 1];
    double[] values = new double[simulations];
    Arrays.fill(values, Math.max(0, schedule.start()));
    double[] sorted = new double[simulations];
    Random rng = new Random(PLAN_SEED);
    for (int k = 0; k <= n; k++) {
      if (k > 0) {
        for (int s = 0; s < simulations; s++) {
          double v = Projection.flowValue(values[s], schedule.payment()[k]);
          v = Projection.flowValue(v, schedule.oneOff()[k]);
          values[s] = v * Math.exp(mean + sd * rng.nextGaussian());
        }
      }
      System.arraycopy(values, 0, sorted, 0, simulations);
      Arrays.sort(sorted);
      p5[k] = sorted[(int) Math.floor(0.05 * (simulations - 1))];
      p50[k] = sorted[(int) Math.floor(0.50 * (simulations - 1))];
      p95[k] = sorted[(int) Math.floor(0.95 * (simulations - 1))];
    }
    return new Band(logReturns.size(), mean, sd, simulations, p5, p50, p95);
  }

  private double computeStdDev(List<Double> values, double mean) {
    double variance = values.stream()
        .mapToDouble(v -> (v - mean) * (v - mean))
        .average()
        .orElse(0);
    return Math.sqrt(variance);
  }
}
