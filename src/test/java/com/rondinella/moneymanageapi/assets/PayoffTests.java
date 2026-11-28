package com.rondinella.moneymanageapi.assets;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** When a debt ends and what the assets page accepts, on cases worked out by hand. */
class PayoffTests {

  private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);

  private static Assets.Payoff payoff(String balance, String rate, String payment, LocalDate end) {
    return Assets.payoff(balance == null ? null : new BigDecimal(balance), rate == null ? null : new BigDecimal(rate),
        payment == null ? null : new BigDecimal(payment), end, TODAY);
  }

  @Test
  void withoutInterestItIsTheBalanceOverThePaymentRoundedUp() {
    // 1000 / 300 = 3.33: three payments of 300 and a last one of 100
    Assets.Payoff p = payoff("1000", null, "300", null);
    assertEquals("ESTIMATE", p.kind());
    assertEquals(4, p.monthsLeft());
    assertEquals(LocalDate.of(2026, 5, 15), p.expectedEnd());
    // exactly 4 payments of 250, also with a rate of 0
    assertEquals(4, payoff("1000", "0", "250", null).monthsLeft());
  }

  @Test
  void withInterestItIsTheLengthOfAnAmortizingLoan() {
    // 10000 at 6% a year (0.5% a month) paying 300: n = -ln(1 - 0.005 * 10000 / 300) / ln(1.005) = 36.56 -> 37
    Assets.Payoff p = payoff("10000", "6", "300", null);
    assertEquals("ESTIMATE", p.kind());
    assertEquals(37, p.monthsLeft());
    assertEquals(LocalDate.of(2029, 2, 15), p.expectedEnd());
    // the sample car loan: 6200 at 5.9% paying 280 -> 23.5 -> 24
    assertEquals(24, payoff("6200", "5.9", "280", null).monthsLeft());
  }

  @Test
  void aPaymentThatDoesNotCoverTheInterestNeverEnds() {
    // 10000 at 12% a year: 100 of interest a month
    assertEquals("NEVER", payoff("10000", "12", "100", null).kind());
    assertEquals("NEVER", payoff("10000", "12", "99", null).kind());
    assertEquals("ESTIMATE", payoff("10000", "12", "101", null).kind());
    // more than a hundred years is never too
    assertEquals("NEVER", payoff("1000000", null, "1", null).kind());
  }

  @Test
  void anEndDateWinsAndNothingLeftIsPaid() {
    Assets.Payoff p = payoff("5000", "3", "100", LocalDate.of(2027, 3, 1));
    assertEquals("END_DATE", p.kind());
    assertEquals(LocalDate.of(2027, 3, 1), p.expectedEnd());
    assertEquals(14, p.monthsLeft(), "13 months and a half, rounded up");
    assertEquals(-2, payoff("5000", null, null, LocalDate.of(2025, 11, 15)).monthsLeft(), "already passed");
    assertEquals("PAID", payoff("0", "3", "100", LocalDate.of(2027, 3, 1)).kind());
    assertEquals("UNKNOWN", payoff("5000", "3", null, null).kind());
    assertEquals("UNKNOWN", payoff("5000", "3", "0", null).kind());
  }

  @Test
  void monthsBetweenRoundUp() {
    assertEquals(0, Assets.monthsBetween(TODAY, TODAY));
    assertEquals(1, Assets.monthsBetween(TODAY, TODAY.plusDays(1)));
    assertEquals(1, Assets.monthsBetween(TODAY, TODAY.plusMonths(1)));
    assertEquals(2, Assets.monthsBetween(TODAY, TODAY.plusMonths(1).plusDays(1)));
    assertEquals(-1, Assets.monthsBetween(TODAY, TODAY.minusDays(3)));
  }

  private static String code(Runnable check) {
    ResponseStatusException e = assertThrows(ResponseStatusException.class, check::run);
    assertEquals(400, e.getStatusCode().value());
    return e.getReason();
  }

  @Test
  void theRulesOfWhatIsSaved() {
    assertEquals("Casa", Checks.name("  Casa "));
    assertEquals("NAME_REQUIRED", code(() -> Checks.name("   ")));
    assertEquals("NAME_REQUIRED", code(() -> Checks.name(null)));
    assertEquals("NAME_TOO_LONG", code(() -> Checks.name("x".repeat(201))));
    assertNull(Checks.text("  "));
    assertEquals("TEXT_TOO_LONG", code(() -> Checks.text("x".repeat(301))));

    assertEquals("AMOUNT_REQUIRED", code(() -> Checks.amount(null)));
    assertEquals("AMOUNT_RANGE", code(() -> Checks.amount(new BigDecimal("-0.01"))));
    assertEquals("AMOUNT_RANGE", code(() -> Checks.amount(new BigDecimal("1.005"))));
    assertEquals("AMOUNT_RANGE", code(() -> Checks.amount(new BigDecimal("1000000000000.01"))));
    assertEquals(0, new BigDecimal("12.50").compareTo(Checks.amount(new BigDecimal("12.500"))));
    assertNull(Checks.optionalAmount(null));

    assertEquals("RATE_RANGE", code(() -> Checks.rate(new BigDecimal("-1"))));
    assertEquals("RATE_RANGE", code(() -> Checks.rate(new BigDecimal("100.001"))));
    assertEquals("RATE_RANGE", code(() -> Checks.rate(new BigDecimal("2.1234"))));
    assertEquals(0, new BigDecimal("2.125").compareTo(Checks.rate(new BigDecimal("2.125"))));

    assertEquals("USD", Checks.currency(" usd "));
    assertEquals("CURRENCY", code(() -> Checks.currency("EURO")));
    assertEquals("CURRENCY", code(() -> Checks.currency("QQQ")));
    assertEquals("CURRENCY", code(() -> Checks.currency(null)));

    assertEquals("DATE_IN_FUTURE", code(() -> Checks.past(TODAY.plusDays(1), TODAY)));
    assertEquals(TODAY, Checks.past(TODAY, TODAY));
    assertEquals("DATE_RANGE", code(() -> Checks.date(LocalDate.of(1899, 12, 31), TODAY)));
    assertEquals("DATE_RANGE", code(() -> Checks.date(TODAY.plusYears(100).plusDays(1), TODAY)));
  }
}
