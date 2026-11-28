package com.rondinella.moneymanageapi.investments;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** The returns on cases worked out by hand. */
class ReturnsTests {

  private static final LocalDate DAY = LocalDate.of(2023, 1, 2);

  private static Returns.Flow flow(int days, double amount) {
    return new Returns.Flow(DAY.plusDays(days), amount);
  }

  @Test
  void oneBuyWorthTenPercentMoreAYearLaterIsTenPercentAYear() {
    assertEquals(0.10, Returns.xirr(List.of(flow(0, -1000), flow(365, 1100))), 1e-9);
  }

  @Test
  void twoBuysAYearApartBothAtTenPercent() {
    // 1000 * 1.1^2 + 1000 * 1.1 = 1210 + 1100
    assertEquals(0.10, Returns.xirr(List.of(flow(0, -1000), flow(365, -1000), flow(730, 2310))), 1e-9);
  }

  @Test
  void aLossAndHalfAYear() {
    // 1000 -> 900 in two years: 0.9^(1/2) - 1
    assertEquals(Math.sqrt(0.9) - 1, Returns.xirr(List.of(flow(0, -1000), flow(730, 900))), 1e-9);
    // a sale in the middle: -1000, +550 after a year, +605 after two = 10%
    assertEquals(0.10, Returns.xirr(List.of(flow(0, -1000), flow(365, 550), flow(730, 605))), 1e-9);
    // 1000 -> 1050 in half a year (182.5 days is not a day: 1.05^(365/182) - 1)
    assertEquals(Math.pow(1.05, 365.0 / 182) - 1, Returns.xirr(List.of(flow(0, -1000), flow(182, 1050))), 1e-9);
  }

  @Test
  void withoutMoneyInAndOutThereIsNoRate() {
    assertNull(Returns.xirr(List.of()));
    assertNull(Returns.xirr(List.of(flow(0, -1000))));
    assertNull(Returns.xirr(List.of(flow(0, -1000), flow(10, -50))));
    assertNull(Returns.xirr(List.of(flow(0, 1000))));
    // everything lost: -100%, not found by a rate above -1
    Double lost = Returns.xirr(List.of(flow(0, -1000), flow(365, 0.0001)));
    assertTrue(lost == null || lost < -0.99, String.valueOf(lost));
  }

  @Test
  void timeWeightedChainsThePeriodsWhateverWasPaidIn() {
    // 1000 in on day 1, worth 1100 at the end of day 31 (+10%); 900 more on day 32, worth 2100 at the end of day 62:
    // (2100 - 1100 - 900) / (1100 + 900) = +5%; together 1.10 * 1.05 - 1
    NavigableMap<LocalDate, Double> worth = new TreeMap<>();
    worth.put(DAY.plusDays(1), 1000.0);
    worth.put(DAY.plusDays(31), 1100.0);
    worth.put(DAY.plusDays(62), 2100.0);
    List<Returns.Flow> flows = List.of(flow(1, -1000), flow(32, -900));
    assertEquals(0.155, Returns.timeWeighted(DAY, worth, flows), 1e-9);
  }

  @Test
  void timeWeightedWeighsAFlowInsideAPeriodByTheDaysItWasThere() {
    // period of 10 days worth 1000 at the start; 500 in at the start of day 6 (5 of 10 days there), worth 1600 at the end:
    // (1600 - 1000 - 500) / (1000 + 500 * 5 / 10) = 100 / 1250
    NavigableMap<LocalDate, Double> worth = new TreeMap<>();
    worth.put(DAY, 1000.0);
    worth.put(DAY.plusDays(10), 1600.0);
    List<Returns.Flow> flows = List.of(flow(0, -1000), flow(6, -500));
    double first = 0; // 1000 in on DAY, worth 1000 at its end
    assertEquals((1 + first) * (1 + 100.0 / 1250) - 1, Returns.timeWeighted(DAY.minusDays(1), worth, flows), 1e-9);
    // a sale (money out) counts the same way, with the other sign
    NavigableMap<LocalDate, Double> after = new TreeMap<>(worth);
    after.put(DAY.plusDays(20), 1100.0);
    List<Returns.Flow> withSale = List.of(flow(0, -1000), flow(6, -500), flow(11, 550));
    // (1100 - 1600 + 550) / (1600 - 550 * 10 / 10)
    assertEquals((1 + 100.0 / 1250) * (1 + 50.0 / 1050) - 1, Returns.timeWeighted(DAY.minusDays(1), after, withSale), 1e-9);
  }

  @Test
  void yearlyRate() {
    assertEquals(0.10, Returns.yearly(0.4641, 365 * 4), 1e-12);
    assertEquals(0.10, Returns.yearly(0.21, 730), 1e-12);
    assertEquals(0.10, Returns.yearly(0.10, 365), 1e-12);
    assertNull(Returns.yearly(null, 365));
    assertNull(Returns.yearly(0.1, 0));
  }

  @Test
  void taxOnlyOnAGain() {
    assertEquals(new BigDecimal("26.00"), Portfolio.tax(new BigDecimal("100")));
    assertEquals(new BigDecimal("0.00"), Portfolio.tax(new BigDecimal("-100")));
    assertEquals(new BigDecimal("116.22"), Portfolio.tax(new BigDecimal("447")));
  }
}
