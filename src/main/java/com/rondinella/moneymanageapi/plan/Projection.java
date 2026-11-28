package com.rondinella.moneymanageapi.plan;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The arithmetic of a plan, month by month, without the database (the rules of Raoul's spreadsheet, without its
 * mistakes). Month 0 is the month of {@code from} (today, or the day a version was saved) with the value it starts
 * from; every following month k up to the month of the target date:
 * <ol>
 *   <li>the monthly payment goes in (the base payment plus every change of the payment from that month on);</li>
 *   <li>the one-off events of that month (money in or out);</li>
 *   <li>the month grows: V = V x (1 + r_m) with r_m = (1 + r)^(1/12) - 1, the monthly rate equivalent to the yearly r
 *   (not r / 12, which would give more than r in a year).</li>
 * </ol>
 * Money in adds to the value and to the money put in ({@code invested}); money out takes from the value (never below
 * zero) and makes the money put in smaller in the same proportion (a sale takes out its share of the cost). Taxes:
 * {@code rate x max(0, value - invested)}, only on the gain. Euro of today: divided by (1 + inflation)^years, with the
 * exact years from {@code from} to the target date (whole years, then the days left over the days of that year).
 */
public final class Projection {

  /** ONE_OFF: an amount once, in the month of its date; MONTHLY: the monthly payment changes by it from that month on */
  public enum Kind { ONE_OFF, MONTHLY }

  /** {@code amount}: positive money in, negative money out (for MONTHLY: the change of the payment, either way) */
  public record Event(Kind kind, LocalDate date, double amount) {
  }

  /**
   * What a plan starts from and what it adds: rates are fractions (0.05 = 5% a year). The payments of month k are
   * {@code payment[k]}, the one-off events {@code oneOff[k]}; index 0 is the starting month and has none.
   */
  public record Schedule(LocalDate from, LocalDate target, double start, double invested, double[] payment,
                         double[] oneOff) {

    public int months() {
      return payment.length - 1;
    }
  }

  /** One scenario: the value and the money put in at every month (index 0 = the start). */
  public record Path(double rate, double[] value, double[] invested) {

    public double end() {
      return value[value.length - 1];
    }

    public double endInvested() {
      return invested[invested.length - 1];
    }
  }

  private Projection() {
  }

  /** months from the month of {@code from} to the month of {@code to} (0 = the same month, negative = before) */
  public static int monthIndex(LocalDate from, LocalDate to) {
    return (int) ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to));
  }

  /**
   * The payments and the events month by month, from {@code from} to the month of {@code target}. Events in the month
   * of {@code from} or before, or after the target month, do not count.
   */
  public static Schedule schedule(LocalDate from, LocalDate target, double start, double invested, double monthly,
                                  List<Event> events) {
    int months = Math.max(0, monthIndex(from, target));
    double[] payment = new double[months + 1];
    double[] oneOff = new double[months + 1];
    double[] change = new double[months + 2];
    for (Event e : events) {
      int k = monthIndex(from, e.date());
      if (k < 1 || k > months) {
        continue;
      }
      if (e.kind() == Kind.MONTHLY) {
        change[k] += e.amount();
      } else {
        oneOff[k] += e.amount();
      }
    }
    double current = monthly;
    for (int k = 1; k <= months; k++) {
      current += change[k];
      payment[k] = current;
    }
    return new Schedule(from, target, start, invested, payment, oneOff);
  }

  /** the monthly rate that gives {@code yearly} in twelve months */
  public static double monthlyRate(double yearly) {
    return Math.pow(1 + yearly, 1.0 / 12) - 1;
  }

  public static Path path(Schedule s, double yearlyRate) {
    int n = s.months();
    double rm = monthlyRate(yearlyRate);
    double[] value = new double[n + 1];
    double[] invested = new double[n + 1];
    double v = Math.max(0, s.start());
    double b = Math.max(0, s.invested());
    value[0] = v;
    invested[0] = b;
    for (int k = 1; k <= n; k++) {
      double[] vb = flow(v, b, s.payment()[k]);
      vb = flow(vb[0], vb[1], s.oneOff()[k]);
      v = vb[0] * (1 + rm);
      b = vb[1];
      value[k] = v;
      invested[k] = b;
    }
    return new Path(yearlyRate, value, invested);
  }

  /**
   * Money in or out of value {@code v} with money put in {@code b}: in adds to both; out takes from the value, at
   * most all of it, and the money put in goes down in the same proportion.
   */
  static double[] flow(double v, double b, double amount) {
    if (amount >= 0) {
      return new double[]{v + amount, b + amount};
    }
    if (v <= 0) {
      return new double[]{0, 0};
    }
    double out = Math.min(-amount, v);
    double left = (v - out) / v;
    return new double[]{v - out, b * left};
  }

  /** only the value of {@link #flow}: money in adds, money out takes at most all of it */
  public static double flowValue(double v, double amount) {
    return amount >= 0 ? v + amount : Math.max(0, v + amount);
  }

  /** the tax on the gain if it were all sold: {@code rate} of value - invested, when positive */
  public static double tax(double value, double invested, double rate) {
    return Math.max(0, value - invested) * rate;
  }

  /** what {@code amount} at {@code target} is worth in money of {@code from}, with {@code inflation} a year */
  public static double real(double amount, LocalDate from, LocalDate target, double inflation) {
    return amount / Math.pow(1 + inflation, years(from, target));
  }

  /**
   * The exact years from {@code from} to {@code to}: whole years, plus the days left over the length of the year
   * after them (2026-11-28 to 2028-11-28 = 2; to 2029-02-28 = 2 + 92 / 365).
   */
  public static double years(LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      return -years(to, from);
    }
    long whole = ChronoUnit.YEARS.between(from, to);
    LocalDate anchor = from.plusYears(whole);
    LocalDate next = from.plusYears(whole + 1);
    return whole + (double) ChronoUnit.DAYS.between(anchor, to) / ChronoUnit.DAYS.between(anchor, next);
  }
}
