package com.rondinella.moneymanageapi.montecarlo;

import com.rondinella.moneymanageapi.brokertransactions.BrokerTransactionService;
import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.common.dtos.GraphPointsDto;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

@Service
public class MonteCarloService {

  private final BrokerTransactionService brokerTransactionService;

  public MonteCarloService(BrokerTransactionService brokerTransactionService) {
    this.brokerTransactionService = brokerTransactionService;
  }

  /**
   * Runs a Monte Carlo simulation projecting portfolio worth into the future.
   *
   * @param years       number of years to project
   * @param simulations number of simulation paths
   * @return GraphPointsDto with percentile curves: P5, P25, P50, P75, P95
   */
  public GraphPointsDto simulate(int years, int simulations) {
    // 1. Get historical monthly returns from the broker worth graph
    Timestamp from = Utils.stringToTimestamp("2021-01-01");
    Timestamp to = Utils.todayAsTimestamp();
    GraphPointsDto historical = brokerTransactionService.worthGraph(from, to);

    List<BigDecimal> totalWorthSeries = extractTotalWorthSeries(historical);

    // Need at least 2 data points to compute returns
    if (totalWorthSeries.size() < 2) {
      return new GraphPointsDto();
    }

    // 2. Compute monthly log-returns
    List<Double> logReturns = new ArrayList<>();
    for (int i = 1; i < totalWorthSeries.size(); i++) {
      BigDecimal prev = totalWorthSeries.get(i - 1);
      BigDecimal curr = totalWorthSeries.get(i);
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

    // 3. Run simulations
    Random rng = new Random(42);
    double[][] paths = new double[simulations][months + 1];
    for (int s = 0; s < simulations; s++) {
      paths[s][0] = startValue;
      for (int m = 1; m <= months; m++) {
        double shock = meanReturn + stdDev * rng.nextGaussian();
        paths[s][m] = paths[s][m - 1] * Math.exp(shock);
      }
    }

    // 4. Extract percentiles at each time step
    int[] percentileValues = {5, 25, 50, 75, 95};
    GraphPointsDto result = new GraphPointsDto();

    LocalDate today = LocalDate.now();
    for (int m = 0; m <= months; m++) {
      String label = today.plusMonths(m).withDayOfMonth(1).toString();
      double[] colValues = new double[simulations];
      for (int s = 0; s < simulations; s++) {
        colValues[s] = paths[s][m];
      }
      Arrays.sort(colValues);

      for (int pct : percentileValues) {
        String seriesName = "P" + pct;
        int idx = (int) Math.floor(pct / 100.0 * (simulations - 1));
        result.addPoint(seriesName, label, BigDecimal.valueOf(colValues[idx]).setScale(2, RoundingMode.HALF_UP));
      }
    }

    return result;
  }

  private List<BigDecimal> extractTotalWorthSeries(GraphPointsDto graphPoints) {
    Map<String, BigDecimal> totalWorthGraph = graphPoints.getGraph("Total Worth");
    if (totalWorthGraph == null || totalWorthGraph.isEmpty()) {
      return Collections.emptyList();
    }
    return new ArrayList<>(totalWorthGraph.values());
  }

  private double computeStdDev(List<Double> values, double mean) {
    double variance = values.stream()
        .mapToDouble(v -> (v - mean) * (v - mean))
        .average()
        .orElse(0);
    return Math.sqrt(variance);
  }
}
