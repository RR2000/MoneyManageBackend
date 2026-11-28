package com.rondinella.moneymanageapi.montecarlo;

import com.rondinella.moneymanageapi.investments.Portfolio;
import com.rondinella.moneymanageapi.plan.Projection;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The Monte Carlo band of a plan ({@link #band}) from the real monthly returns of the portfolio ({@link #marketReturns}).
 */
public final class MonteCarloService {

  private MonteCarloService() {
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
}
