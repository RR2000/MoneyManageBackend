package com.rondinella.moneymanageapi.categories;

import com.rondinella.moneymanageapi.categories.FixedCosts.Cadence;
import com.rondinella.moneymanageapi.categories.FixedCosts.Row;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The numbers of the fixed costs on cases made by hand. */
class FixedCostsTests {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);

  private static List<LocalDate> days(String... isoDays) {
    return Arrays.stream(isoDays).map(LocalDate::parse).toList();
  }

  private static void same(String expected, BigDecimal actual) {
    assertNotNull(actual, "expected " + expected);
    assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " != " + actual);
  }

  private static Category category(long id, String name) {
    Category c = new Category();
    c.setId(id);
    c.setName(name);
    c.setFixed(true);
    return c;
  }

  private static Row row(String day, String amount, long category, Long rule) {
    return row(day, amount, "EUR", category, rule);
  }

  private static Row row(String day, String amount, String currency, long category, Long rule) {
    return new Row(LocalDate.parse(day), new BigDecimal(amount), currency, category, rule);
  }

  @Test
  void theCadenceComesFromTheGapsBetweenPayments() {
    assertEquals(Cadence.MONTHLY, FixedCosts.cadence(days("2026-07-01", "2026-08-01", "2026-09-01")));
    assertEquals(Cadence.UNKNOWN, FixedCosts.cadence(days("2026-08-01", "2026-09-01")), "monthly needs 3 payments");
    assertEquals(Cadence.BIMONTHLY, FixedCosts.cadence(days("2026-03-12", "2026-05-12", "2026-07-13", "2026-09-12")));
    assertEquals(Cadence.QUARTERLY, FixedCosts.cadence(days("2026-01-15", "2026-04-15", "2026-07-15")));
    assertEquals(Cadence.SEMIANNUAL, FixedCosts.cadence(days("2025-12-01", "2026-06-01")), "half-yearly: 2 are enough");
    assertEquals(Cadence.ANNUAL, FixedCosts.cadence(days("2025-03-15", "2026-03-16")), "yearly: 2 are enough");
    assertEquals(Cadence.UNKNOWN, FixedCosts.cadence(days("2026-11-01")));
    assertEquals(Cadence.UNKNOWN, FixedCosts.cadence(days("2026-01-01", "2026-01-20", "2026-03-30", "2026-04-02")),
        "irregular");
    // a month skipped: one gap of two months among monthly ones is still monthly (4 of 5 gaps in the band)
    assertEquals(Cadence.MONTHLY, FixedCosts.cadence(days("2026-04-01", "2026-05-01", "2026-06-01", "2026-08-01",
        "2026-09-01", "2026-10-01")));
    // half and half: not sure
    assertEquals(Cadence.UNKNOWN, FixedCosts.cadence(days("2026-01-01", "2026-02-01", "2026-04-01", "2026-05-01",
        "2026-07-01")));
  }

  @Test
  void medianAndSetAsideRoundedUpToTenEuro() {
    same("20.00", FixedCosts.median(List.of(new BigDecimal("10"), new BigDecimal("30"), new BigDecimal("20"))));
    same("15.00", FixedCosts.median(List.of(new BigDecimal("10"), new BigDecimal("20"))));
    assertNull(FixedCosts.median(List.of()));
    same("620", FixedCosts.setAside(new BigDecimal("612.40")));
    same("610", FixedCosts.setAside(new BigDecimal("610.00")));
    same("10", FixedCosts.setAside(new BigDecimal("0.01")));
    same("0", FixedCosts.setAside(BigDecimal.ZERO));
    same("0", FixedCosts.setAside(new BigDecimal("-5")));
    assertNull(FixedCosts.setAside(null));
  }

  /**
   * Rent 700 on the 1st of every month since January 2025 with a refund of 40 in May 2026, the car insurance 540 every
   * 15 March, 10 USD once; the period is November 2025 to October 2026.
   */
  @Test
  void averagesYearAndSetAsideOverTheLastTwelveCompleteMonths() {
    List<Row> rows = new ArrayList<>();
    for (LocalDate d = LocalDate.of(2025, 1, 1); !d.isAfter(TODAY); d = d.plusMonths(1)) {
      rows.add(row(d.toString(), "-700", 1, 10L));
    }
    rows.add(row("2026-05-10", "40", 1, null));
    rows.add(row("2025-03-15", "-540", 2, 20L));
    rows.add(row("2026-03-15", "-540", 2, 20L));
    rows.add(row("2026-06-01", "-10", "USD", 1, 10L));
    FixedCosts.Answer a = FixedCosts.compute(TODAY, 12, List.of(category(1, "Casa"), category(2, "Assicurazioni")),
        Map.of(10L, "affitto", 20L, "assicurazione"), rows, LocalDate.of(2024, 1, 5), 3);

    assertEquals(12, a.monthsUsed());
    assertEquals(LocalDate.of(2025, 11, 1), a.from());
    assertEquals(LocalDate.of(2026, 10, 31), a.to());
    assertEquals("2026-11", a.month());
    same("741.67", a.monthly());                  // (12 x 700 - 40 + 540) / 12
    same("8900.00", a.yearly());
    same("750", a.setAside());
    assertEquals(10, a.roundingStep());
    same("700.00", a.thisMonth());
    assertEquals(3, a.uncategorized());

    assertEquals("Casa", a.categories().get(0).name(), "the largest first");
    same("8360.00", a.categories().get(0).total());
    same("696.67", a.categories().get(0).monthly());
    same("8360.00", a.categories().get(0).yearly());
    same("700.00", a.categories().get(0).thisMonth());
    assertEquals(13, a.categories().get(0).movements(), "12 rents and the refund");
    same("45.00", a.categories().get(1).monthly());
    same("540.00", a.categories().get(1).yearly());
    same("0.00", a.categories().get(1).thisMonth());

    assertEquals(1, a.otherCurrencies().size());
    assertEquals("USD", a.otherCurrencies().get(0).currency());
    same("10.00", a.otherCurrencies().get(0).total());
    same("0.83", a.otherCurrencies().get(0).monthly());

    List<FixedCosts.Upcoming> next = a.upcoming();
    assertEquals(3, next.size());
    assertEquals("affitto", next.get(0).rule());
    assertEquals(Cadence.MONTHLY, next.get(0).cadence());
    assertEquals(LocalDate.of(2026, 12, 1), next.get(0).expectedOn());
    same("700.00", next.get(0).amount());
    assertFalse(next.get(0).late());
    assertEquals("Assicurazioni", next.get(1).category());
    assertEquals(Cadence.ANNUAL, next.get(1).cadence());
    assertEquals(LocalDate.of(2027, 3, 15), next.get(1).expectedOn());
    same("540.00", next.get(1).amount());
    assertEquals(2, next.get(1).payments());
    assertEquals("USD", next.get(2).currency(), "not known: last");
    assertEquals(Cadence.UNKNOWN, next.get(2).cadence());
    assertNull(next.get(2).expectedOn());
  }

  @Test
  void thePeriodStartsWithTheFirstCompleteMonthOfData() {
    List<Category> fixed = List.of(category(1, "Casa"));
    List<Row> rows = List.of(row("2026-07-01", "-300", 1, null), row("2026-10-01", "-300", 1, null));
    FixedCosts.Answer mid = FixedCosts.compute(TODAY, 12, fixed, Map.of(), rows, LocalDate.of(2026, 6, 15), 0);
    assertEquals(LocalDate.of(2026, 7, 1), mid.from());
    assertEquals(4, mid.monthsUsed());
    same("150.00", mid.monthly());
    same("1800.00", mid.yearly());
    FixedCosts.Answer first = FixedCosts.compute(TODAY, 12, fixed, Map.of(), rows, LocalDate.of(2026, 6, 1), 0);
    assertEquals(5, first.monthsUsed());
    same("120.00", first.monthly());
    FixedCosts.Answer three = FixedCosts.compute(TODAY, 3, fixed, Map.of(), rows, LocalDate.of(2020, 1, 1), 0);
    assertEquals(LocalDate.of(2026, 8, 1), three.from());
    same("100.00", three.monthly());

    FixedCosts.Answer none = FixedCosts.compute(TODAY, 12, fixed, Map.of(), List.of(), null, 0);
    assertEquals(0, none.monthsUsed());
    assertNull(none.monthly());
    assertNull(none.setAside());
    FixedCosts.Answer thisMonthOnly = FixedCosts.compute(TODAY, 12, fixed, Map.of(),
        List.of(row("2026-11-03", "-9", 1, null)), LocalDate.of(2026, 11, 3), 0);
    assertEquals(0, thisMonthOnly.monthsUsed());
    same("9.00", thisMonthOnly.thisMonth());
  }

  @Test
  void aPaymentAWholeCadenceLateHasStoppedOneLessIsLate() {
    List<Category> fixed = List.of(category(1, "Palestra"), category(2, "Streaming"));
    List<Row> rows = List.of(
        row("2026-07-01", "-40", 1, 1L), row("2026-08-01", "-40", 1, 1L), row("2026-09-01", "-40", 1, 1L),
        row("2026-08-20", "-9.99", 2, 2L), row("2026-09-20", "-9.99", 2, 2L), row("2026-10-20", "-12.99", 2, 2L),
        row("2026-10-21", "-1", 2, 2L));
    List<FixedCosts.Upcoming> next = FixedCosts.compute(TODAY, 12, fixed, Map.of(1L, "palestra", 2L, "stream"), rows,
        LocalDate.of(2026, 1, 1), 0).upcoming();
    assertEquals(1, next.size(), "the gym stopped after September");
    // 20 and 21 October are two payments: the gaps 31, 30, 1 are monthly for 2 of 3 (at least 60%): monthly, from
    // the last payment
    assertEquals(Cadence.MONTHLY, next.getFirst().cadence());
    assertEquals(LocalDate.of(2026, 11, 21), next.getFirst().expectedOn());
    assertEquals(4, next.getFirst().payments());

    List<FixedCosts.Upcoming> late = FixedCosts.compute(TODAY, 12, fixed, Map.of(2L, "stream"), rows.subList(3, 6),
        LocalDate.of(2026, 1, 1), 0).upcoming();
    assertEquals(Cadence.MONTHLY, late.getFirst().cadence());
    assertEquals(LocalDate.of(2026, 11, 20), late.getFirst().expectedOn());
    assertTrue(late.getFirst().late());
    same("9.99", late.getFirst().amount());
  }
}
