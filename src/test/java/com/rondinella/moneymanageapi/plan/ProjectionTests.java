package com.rondinella.moneymanageapi.plan;

import com.rondinella.moneymanageapi.investments.Portfolio;
import com.rondinella.moneymanageapi.montecarlo.MonteCarloService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The arithmetic of the plan, with numbers worked out by hand. */
class ProjectionTests {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
  /** the yearly rate whose monthly rate is exactly 1% */
  private static final double ONE_PERCENT_A_MONTH = Math.pow(1.01, 12) - 1;

  private static Projection.Schedule schedule(LocalDate target, double start, double invested, double monthly,
                                              Projection.Event... events) {
    return Projection.schedule(TODAY, target, start, invested, monthly, List.of(events));
  }

  @Test
  void theMonthlyRateGivesTheYearlyOneInTwelveMonthsNotRateOverTwelve() {
    Projection.Path path = Projection.path(schedule(TODAY.plusYears(1), 10_000, 10_000, 0), 0.05);
    assertEquals(12, path.value().length - 1);
    // (1 + r_m)^12 = 1.05 exactly; r / 12 would give 10 511.62
    assertEquals(10_500.00, path.end(), 1e-6);
    assertEquals(0.0040741238, Projection.monthlyRate(0.05), 1e-9);
  }

  @Test
  void everyMonthThePaymentGoesInThenTheMonthGrows() {
    // V1 = (1000 + 100) x 1.01 = 1111; V2 = (1111 + 100) x 1.01 = 1223.11
    Projection.Path path = Projection.path(schedule(TODAY.plusMonths(2), 1000, 1000, 100), ONE_PERCENT_A_MONTH);
    assertArrayEquals(new double[]{1000, 1111, 1223.11}, path.value(), 1e-9);
    assertArrayEquals(new double[]{1000, 1100, 1200}, path.invested(), 1e-9);
  }

  @Test
  void withoutGrowthThePaymentsAddUp() {
    Projection.Path path = Projection.path(schedule(TODAY.plusYears(2), 1000, 800, 100), 0);
    assertEquals(3400, path.end(), 1e-9);
    assertEquals(3200, path.endInvested(), 1e-9);
  }

  @Test
  void moneyOutTakesItsShareOfWhatWasPutIn() {
    // 12 000 worth, 10 000 put in, 3 000 out in February: 9 000 left, put in 10 000 x 9 000 / 12 000 = 7 500
    Projection.Path path = Projection.path(schedule(TODAY.plusMonths(4), 12_000, 10_000, 0,
        new Projection.Event(Projection.Kind.ONE_OFF, LocalDate.of(2027, 2, 10), -3000)), 0);
    assertArrayEquals(new double[]{12_000, 12_000, 12_000, 9_000, 9_000}, path.value(), 1e-9);
    assertEquals(7_500, path.endInvested(), 1e-9);
    // taxes only on the gain: 26% of 1 500
    assertEquals(390, Projection.tax(path.end(), path.endInvested(), 0.26), 1e-9);
    assertEquals(0, Projection.tax(900, 1000, 0.26), 1e-9);
  }

  @Test
  void moneyInAddsToBothAndMoreOutThanThereIsEmptiesIt() {
    Projection.Path in = Projection.path(schedule(TODAY.plusMonths(2), 1000, 500, 0,
        new Projection.Event(Projection.Kind.ONE_OFF, LocalDate.of(2027, 1, 1), 2000)), 0);
    assertEquals(3000, in.end(), 1e-9);
    assertEquals(2500, in.endInvested(), 1e-9);
    Projection.Path out = Projection.path(schedule(TODAY.plusMonths(2), 1000, 500, 0,
        new Projection.Event(Projection.Kind.ONE_OFF, LocalDate.of(2026, 12, 1), -5000)), 0.05);
    assertEquals(0, out.end(), 1e-9);
    assertEquals(0, out.endInvested(), 1e-9);
  }

  @Test
  void aChangeOfThePaymentCountsFromItsMonthOn() {
    // 100 a month, +250 from February: 100 + 100 + 350 + 350
    Projection.Schedule s = schedule(TODAY.plusMonths(4), 0, 0, 100,
        new Projection.Event(Projection.Kind.MONTHLY, LocalDate.of(2027, 2, 20), 250));
    assertArrayEquals(new double[]{0, 100, 100, 350, 350}, s.payment(), 1e-9);
    assertEquals(900, Projection.path(s, 0).end(), 1e-9);
  }

  @Test
  void eventsOutsideThePlanDoNotCount() {
    Projection.Schedule s = schedule(TODAY.plusMonths(3), 1000, 1000, 0,
        new Projection.Event(Projection.Kind.ONE_OFF, TODAY.withDayOfMonth(30), -500),
        new Projection.Event(Projection.Kind.ONE_OFF, TODAY.minusYears(1), -500),
        new Projection.Event(Projection.Kind.ONE_OFF, TODAY.plusMonths(4), -500),
        new Projection.Event(Projection.Kind.MONTHLY, TODAY.plusMonths(4), 100));
    assertEquals(1000, Projection.path(s, 0).end(), 1e-9);
  }

  @Test
  void euroOfTodayUseTheExactYears() {
    assertEquals(2.0, Projection.years(TODAY, LocalDate.of(2028, 11, 28)), 1e-12);
    assertEquals(2 + 92.0 / 365, Projection.years(TODAY, LocalDate.of(2029, 2, 28)), 1e-12);
    assertEquals(31.0 / 365, Projection.years(TODAY, LocalDate.of(2026, 12, 29)), 1e-12);
    // 10 758.50 after taxes in two years, 2% a year: 10 758.50 / 1.0404
    assertEquals(10_340.73, Projection.real(10_758.50, TODAY, LocalDate.of(2028, 11, 28), 0.02), 0.005);
  }

  @Test
  void theTargetMonthIsTheLastStep() {
    assertEquals(24, schedule(LocalDate.of(2028, 11, 1), 0, 0, 0).months());
    assertEquals(0, schedule(TODAY, 0, 0, 0).months());
    assertEquals(223, schedule(LocalDate.of(2045, 6, 30), 0, 0, 0).months());
  }

  @Test
  void monteCarloWithTheSameReturnEveryMonthIsTheScenarioWithThatReturn() {
    List<Double> returns = Collections.nCopies(12, Math.log(1.01));
    Projection.Schedule s = schedule(TODAY.plusYears(2), 1000, 1000, 100,
        new Projection.Event(Projection.Kind.ONE_OFF, LocalDate.of(2027, 6, 1), -300),
        new Projection.Event(Projection.Kind.MONTHLY, LocalDate.of(2028, 1, 1), 50));
    MonteCarloService.Band band = MonteCarloService.band(returns, s, 500);
    Projection.Path path = Projection.path(s, ONE_PERCENT_A_MONTH);
    assertNotNull(band);
    assertEquals(0, band.monthlyStdDev(), 1e-12);
    assertArrayEquals(path.value(), band.p50(), 1e-6);
    assertArrayEquals(path.value(), band.p5(), 1e-6);
    assertArrayEquals(path.value(), band.p95(), 1e-6);
  }

  @Test
  void monteCarloSpreadsAndIsTheSameEveryTime() {
    List<Double> returns = List.of(0.03, -0.02, 0.01, 0.04, -0.05, 0.02, 0.0, 0.01);
    Projection.Schedule s = schedule(TODAY.plusYears(10), 10_000, 10_000, 200);
    MonteCarloService.Band a = MonteCarloService.band(returns, s, 1000);
    MonteCarloService.Band b = MonteCarloService.band(returns, s, 1000);
    assertArrayEquals(a.p50(), b.p50());
    int end = s.months();
    assertTrue(a.p5()[end] < a.p50()[end] && a.p50()[end] < a.p95()[end]);
    assertEquals(10_000, a.p50()[0], 1e-9);
    assertNull(MonteCarloService.band(returns.subList(0, 5), s, 1000), "fewer than 6 months: no band");
  }

  @Test
  void theMarketReturnsLeaveOutTheMoneyPutIn() {
    List<Portfolio.Point> history = new ArrayList<>();
    history.add(point("2026-01-10", "500", "500"));    // first purchase: not the end of a month
    history.add(point("2026-01-31", "1000", "1000"));
    history.add(point("2026-02-28", "1110", "1100"));  // 100 put in: (1110 - 100) / 1000 = +1%
    history.add(point("2026-03-31", "990", "1100"));   // -10.81%
    history.add(point("2026-04-15", "2000", "1100"));  // today: not complete
    List<Double> r = MonteCarloService.marketReturns(history, LocalDate.of(2026, 4, 15));
    assertEquals(2, r.size());
    assertEquals(Math.log(1.01), r.get(0), 1e-12);
    assertEquals(Math.log(990.0 / 1110), r.get(1), 1e-12);
  }

  private static Portfolio.Point point(String day, String value, String invested) {
    return new Portfolio.Point(LocalDate.parse(day), new BigDecimal(value), new BigDecimal(invested), Map.of());
  }

  private static PlanVersion version(LocalDate savedOn) {
    PlanVersion v = new PlanVersion();
    v.setNumber(3);
    v.setSavedOn(savedOn);
    v.setSavedAt(Instant.parse("2026-01-15T10:00:00Z"));
    v.setTargetDate(LocalDate.of(2030, 1, 1));
    v.setStartValue(new BigDecimal("1000"));
    v.setInvested(new BigDecimal("1000"));
    v.setMonthly(new BigDecimal("100"));
    v.setRateBase(BigDecimal.valueOf(ONE_PERCENT_A_MONTH * 100));
    return v;
  }

  private static PlanSnapshot snapshot(String month, String value) {
    return new PlanSnapshot(1L, LocalDate.parse(month), new BigDecimal(value), BigDecimal.ZERO, Instant.now());
  }

  @Test
  void plannedAgainstRealFromTheMonthOfSaving() {
    PlanVersion v = version(LocalDate.of(2026, 1, 15));
    // no complete month yet: the first comparison comes on 1 February
    Plans.Tracking waiting = Plans.tracking(v, List.of(), List.of());
    assertEquals("WAITING", waiting.status());
    assertEquals(LocalDate.of(2026, 2, 1), waiting.nextCheck());

    // planned: January 1000 (nothing happens in the month of saving), February 1111, March 1223.11
    Plans.Tracking above = Plans.tracking(v, List.of(), List.of(snapshot("2025-12-01", "1"),
        snapshot("2026-01-01", "1000"), snapshot("2026-02-01", "1300")));
    assertEquals("ABOVE", above.status());
    assertEquals("2026-02", above.month());
    assertEquals(0, new BigDecimal("1111.00").compareTo(above.planned()));
    assertEquals(0, new BigDecimal("189.00").compareTo(above.gap()));
    assertEquals(189.0 / 1111, above.gapShare(), 1e-6);
    assertEquals(LocalDate.of(2026, 4, 1), above.nextCheck());
    assertEquals(2, above.points().size(), "the month before saving is not in it");

    Plans.Tracking below = Plans.tracking(v, List.of(), List.of(snapshot("2026-01-01", "1000"),
        snapshot("2026-03-01", "1100")));
    assertEquals("BELOW", below.status());
    assertEquals(0, new BigDecimal("1223.11").compareTo(below.planned()));
    assertNull(below.points().get(1).actual(), "February is missing: no point");

    Plans.Tracking onTrack = Plans.tracking(v, List.of(), List.of(snapshot("2026-03-01", "1200")));
    assertEquals("ON_TRACK", onTrack.status(), "-1.9% is within 5%");
  }
}
