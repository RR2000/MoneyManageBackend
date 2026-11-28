package com.rondinella.moneymanageapi.investments;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * How much the investments gave, from the real movements (no spreadsheet shortcut):
 * <ul>
 *   <li>{@link #xirr}: the money-weighted return, the yearly rate that makes the money paid in (purchases with their
 *   fees), the money taken out (sales, net of fees) and today's value worth zero together. It depends on when the money
 *   went in: it is the return of the investor.</li>
 *   <li>{@link #timeWeighted}: the time-weighted return, the returns of the periods between two valuations chained
 *   together (modified Dietz inside each period): it does not depend on how much was paid in and when, so it is the
 *   return of what was bought.</li>
 * </ul>
 * Money paid in is negative, money taken out and the final value positive. Days count from the start of the day.
 */
public final class Returns {

  private static final double DAYS_A_YEAR = 365.0;
  private static final double TOLERANCE = 1e-10;

  /** Money in or out of the investments on one day: negative paid in, positive taken out. */
  public record Flow(LocalDate date, double amount) {
  }

  private Returns() {
  }

  /**
   * The yearly rate r for which the sum of every flow times (1 + r)^(-days since the first flow / 365) is zero;
   * null when it cannot exist (only money in, only money out, nothing at all) or is not found.
   */
  public static Double xirr(List<Flow> flows) {
    if (flows.stream().noneMatch(f -> f.amount() < 0) || flows.stream().noneMatch(f -> f.amount() > 0)) {
      return null;
    }
    LocalDate first = flows.stream().map(Flow::date).min(LocalDate::compareTo).orElseThrow();
    double[] years = flows.stream().mapToDouble(f -> ChronoUnit.DAYS.between(first, f.date()) / DAYS_A_YEAR).toArray();
    double[] amounts = flows.stream().mapToDouble(Flow::amount).toArray();
    // Newton from 10% a year, then bisection if it wanders off
    double rate = 0.1;
    for (int i = 0; i < 100; i++) {
      double value = npv(rate, years, amounts);
      double slope = 0;
      for (int k = 0; k < amounts.length; k++) {
        slope -= years[k] * amounts[k] * Math.pow(1 + rate, -years[k] - 1);
      }
      if (slope == 0 || !Double.isFinite(slope)) {
        break;
      }
      double next = rate - value / slope;
      if (!Double.isFinite(next) || next <= -1) {
        break;
      }
      if (Math.abs(next - rate) < TOLERANCE) {
        return next;
      }
      rate = next;
    }
    return bisection(years, amounts);
  }

  private static Double bisection(double[] years, double[] amounts) {
    double lo = -0.9999;
    double hi = 1000;
    double fLo = npv(lo, years, amounts);
    double fHi = npv(hi, years, amounts);
    if (!Double.isFinite(fLo) || !Double.isFinite(fHi) || Math.signum(fLo) == Math.signum(fHi)) {
      return null;
    }
    for (int i = 0; i < 300; i++) {
      double mid = (lo + hi) / 2;
      double fMid = npv(mid, years, amounts);
      if (Math.abs(hi - lo) < TOLERANCE) {
        return mid;
      }
      if (Math.signum(fMid) == Math.signum(fLo)) {
        lo = mid;
        fLo = fMid;
      } else {
        hi = mid;
      }
    }
    return (lo + hi) / 2;
  }

  private static double npv(double rate, double[] years, double[] amounts) {
    double sum = 0;
    for (int k = 0; k < amounts.length; k++) {
      sum += amounts[k] * Math.pow(1 + rate, -years[k]);
    }
    return sum;
  }

  /**
   * The time-weighted return from {@code start} (worth 0 at its end) through every valuation (worth at the end of
   * that day, oldest first): the product of (1 + the return of each period) minus 1. The return of a period from a to
   * b is (worth at b - worth at a - money put in) / (worth at a + money put in, each weighted by the share of the
   * period it was there); a flow of day t is there from the start of t. A period with nothing in it counts 0.
   * Null without valuations or flows.
   */
  public static Double timeWeighted(LocalDate start, NavigableMap<LocalDate, Double> valuations, List<Flow> flows) {
    if (valuations.isEmpty() || flows.isEmpty()) {
      return null;
    }
    double growth = 1;
    LocalDate from = start;
    double worthFrom = 0;
    for (Map.Entry<LocalDate, Double> valuation : valuations.entrySet()) {
      LocalDate to = valuation.getKey();
      if (!to.isAfter(from)) {
        continue;
      }
      double length = ChronoUnit.DAYS.between(from, to);
      double putIn = 0;
      double weighted = 0;
      for (Flow flow : flows) {
        if (flow.date().isAfter(from) && !flow.date().isAfter(to)) {
          double in = -flow.amount();
          putIn += in;
          weighted += in * (ChronoUnit.DAYS.between(flow.date(), to) + 1) / length;
        }
      }
      double base = worthFrom + weighted;
      if (base > 0) {
        growth *= 1 + (valuation.getValue() - worthFrom - putIn) / base;
      }
      from = to;
      worthFrom = valuation.getValue();
    }
    return growth - 1;
  }

  /** A return over {@code days} as a yearly rate; null for no period. */
  public static Double yearly(Double total, long days) {
    if (total == null || days <= 0 || total <= -1) {
      return null;
    }
    return Math.pow(1 + total, DAYS_A_YEAR / days) - 1;
  }
}
