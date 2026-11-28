package com.rondinella.moneymanageapi.categories;

import com.rondinella.moneymanageapi.banktransactions.BankTransaction;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The numbers of the fixed costs, from the movements of the categories marked fixed.
 *
 * <ul>
 *   <li>Period: the last N complete months (this month is not complete), starting later when the data start later: a
 *   month counts only if the oldest movement of any account is on its first day or before.</li>
 *   <li>Spent in a category: what went out minus what came back (a refund), in euro. Monthly = spent / months, yearly
 *   = monthly x 12, also for the costs paid once a year: the average spreads them over the months.</li>
 *   <li>To set aside every month: the monthly average of all the fixed costs, rounded up to the next
 *   {@link #STEP} euro.</li>
 *   <li>This month: what went out so far, against the average.</li>
 *   <li>Coming next: for every rule of a fixed category (the movements given by hand to the category without one of
 *   its rules: one group) and currency, the cadence from the dates of all its payments (same day = one payment): the
 *   median gap between them, if at least 60% of the gaps are in the same band, monthly (26-35 days), every two months
 *   (54-68), quarterly (82-100), half-yearly (165-200) or yearly (340-390), with at least 3 payments (2 for half-yearly
 *   and yearly); otherwise the cadence is not known. Expected date = last payment + cadence, amount = median of the
 *   last 6 payments. A payment a whole cadence late has stopped and is left out.</li>
 *   <li>Only euro in the totals; other currencies are summed apart, never converted.</li>
 * </ul>
 */
@Service
public class FixedCosts {

  public static final String EURO = "EUR";
  /** the amount to set aside is rounded up to this many euro */
  public static final int STEP = 10;
  public static final int MAX_MONTHS = 36;
  static final int LAST_PAYMENTS = 6;
  static final int LAST_GAPS = 12;

  /** cadences: months between payments, gaps in days that count as that cadence, payments needed */
  public enum Cadence {
    MONTHLY(1, 26, 35, 3),
    BIMONTHLY(2, 54, 68, 3),
    QUARTERLY(3, 82, 100, 3),
    SEMIANNUAL(6, 165, 200, 2),
    ANNUAL(12, 340, 390, 2),
    UNKNOWN(0, 0, 0, 0);

    final int months;
    final int minDays;
    final int maxDays;
    final int minPayments;

    Cadence(int months, int minDays, int maxDays, int minPayments) {
      this.months = months;
      this.minDays = minDays;
      this.maxDays = maxDays;
      this.minPayments = minPayments;
    }

    boolean fits(long days) {
      return days >= minDays && days <= maxDays;
    }
  }

  /** one movement of a fixed category, with the rule of its category that matches it (null: none) */
  public record Row(LocalDate day, BigDecimal amount, String currency, long categoryId, Long ruleId) {
  }

  public record CategoryCost(long id, String name, BigDecimal total, BigDecimal monthly, BigDecimal yearly,
                             BigDecimal thisMonth, int movements) {
  }

  public record OtherCurrency(String currency, BigDecimal total, BigDecimal monthly, BigDecimal yearly,
                              BigDecimal thisMonth) {
  }

  /**
   * A cost expected: {@code rule} the text of its rule (null: the movements given by hand), {@code expectedOn} null when
   * the cadence is not known, {@code late} when the day has passed and nothing came yet.
   */
  public record Upcoming(long categoryId, String category, Long ruleId, String rule, String currency, Cadence cadence,
                         LocalDate expectedOn, BigDecimal amount, LocalDate last, int payments, boolean late) {
  }

  /**
   * {@code months} asked, {@code monthsUsed} complete months with data in the period ({@code from} to {@code to},
   * both included); the averages are null without a complete month. {@code month}: this month (yyyy-MM).
   */
  public record Answer(String currency, int months, int monthsUsed, LocalDate from, LocalDate to, String month,
                       BigDecimal monthly, BigDecimal yearly, BigDecimal setAside, int roundingStep,
                       BigDecimal thisMonth, List<CategoryCost> categories, List<Upcoming> upcoming,
                       List<OtherCurrency> otherCurrencies, long uncategorized) {
  }

  private final CategoryRepository categories;
  private final CategoryRuleRepository rules;
  private final BankTransactionRepository transactions;
  private final EntityManager em;

  public FixedCosts(CategoryRepository categories, CategoryRuleRepository rules, BankTransactionRepository transactions,
                    EntityManager em) {
    this.categories = categories;
    this.rules = rules;
    this.transactions = transactions;
    this.em = em;
  }

  @Transactional(readOnly = true)
  public Answer of(LocalDate today, int months) {
    List<Category> fixed = categories.findAll().stream().filter(Category::isFixed).toList();
    Map<Long, List<CategoryRule>> rulesOf = new HashMap<>();
    for (CategoryRule rule : Rules.ordered(rules.findAll())) {
      rulesOf.computeIfAbsent(rule.getCategoryId(), k -> new ArrayList<>()).add(rule);
    }
    Map<String, String> currencies = new HashMap<>();
    for (Object[] row : transactions.countByAccountAndCurrency()) {
      currencies.putIfAbsent((String) row[0], (String) row[1]);
    }
    List<Row> rows = new ArrayList<>();
    if (!fixed.isEmpty()) {
      List<BankTransaction> movements = em.createQuery("SELECT t FROM BankTransaction t WHERE t.categoryId IN :ids",
          BankTransaction.class).setParameter("ids", fixed.stream().map(Category::getId).toList()).getResultList();
      for (BankTransaction t : movements) {
        if (t.getAmount() == null || t.getDatetime() == null) {
          continue;
        }
        CategoryRule rule = Rules.first(rulesOf.getOrDefault(t.getCategoryId(), List.of()), t);
        String currency = t.getCurrency() != null ? t.getCurrency() : currencies.getOrDefault(t.getAccount(), EURO);
        rows.add(new Row(t.getDatetime().toLocalDateTime().toLocalDate(), t.getAmount(), currency, t.getCategoryId(),
            rule == null ? null : rule.getId()));
      }
    }
    Timestamp first = em.createQuery("SELECT min(t.datetime) FROM BankTransaction t", Timestamp.class).getSingleResult();
    long uncategorized = em.createQuery("SELECT count(t) FROM BankTransaction t WHERE t.categoryId IS NULL", Long.class)
        .getSingleResult();
    Map<Long, String> patterns = new HashMap<>();
    rulesOf.values().forEach(list -> list.forEach(r -> patterns.put(r.getId(), r.getPattern())));
    return compute(today, months, fixed, patterns, rows, first == null ? null : first.toLocalDateTime().toLocalDate(),
        uncategorized);
  }

  /** the numbers from the rows, without the database: see the class comment */
  public static Answer compute(LocalDate today, int monthsAsked, List<Category> fixed, Map<Long, String> patterns,
                               List<Row> rows, LocalDate firstData, long uncategorized) {
    int months = Math.max(1, Math.min(monthsAsked, MAX_MONTHS));
    YearMonth current = YearMonth.from(today);
    LocalDate to = current.atDay(1);
    LocalDate from = to.minusMonths(months);
    if (firstData == null) {
      from = to;
    } else if (firstData.isAfter(from)) {
      from = firstData.getDayOfMonth() == 1 ? firstData : YearMonth.from(firstData).plusMonths(1).atDay(1);
      if (from.isAfter(to)) {
        from = to;
      }
    }
    int used = (int) ChronoUnit.MONTHS.between(from, to);

    Map<Long, BigDecimal> spent = new HashMap<>();
    Map<Long, BigDecimal> month = new HashMap<>();
    Map<Long, Integer> counts = new HashMap<>();
    Map<String, BigDecimal[]> others = new TreeMap<>();
    for (Row row : rows) {
      boolean inPeriod = !row.day().isBefore(from) && row.day().isBefore(to);
      boolean thisMonth = YearMonth.from(row.day()).equals(current);
      BigDecimal out = row.amount().negate();
      if (EURO.equals(row.currency())) {
        if (inPeriod) {
          spent.merge(row.categoryId(), out, BigDecimal::add);
          counts.merge(row.categoryId(), 1, Integer::sum);
        }
        if (thisMonth) {
          month.merge(row.categoryId(), out, BigDecimal::add);
        }
      } else {
        BigDecimal[] sums = others.computeIfAbsent(row.currency(), c -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        if (inPeriod) {
          sums[0] = sums[0].add(out);
        }
        if (thisMonth) {
          sums[1] = sums[1].add(out);
        }
      }
    }

    List<CategoryCost> costs = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal totalMonth = BigDecimal.ZERO;
    for (Category c : fixed) {
      BigDecimal s = spent.getOrDefault(c.getId(), BigDecimal.ZERO);
      BigDecimal m = month.getOrDefault(c.getId(), BigDecimal.ZERO);
      total = total.add(s);
      totalMonth = totalMonth.add(m);
      costs.add(new CategoryCost(c.getId(), c.getName(), money(s), monthly(s, used), yearly(s, used), money(m),
          counts.getOrDefault(c.getId(), 0)));
    }
    costs.sort(Comparator.comparing((CategoryCost c) -> c.monthly() == null ? BigDecimal.ZERO : c.monthly()).reversed()
        .thenComparing(c -> c.name().toLowerCase()));
    BigDecimal monthly = monthly(total, used);

    List<OtherCurrency> otherCurrencies = new ArrayList<>();
    others.forEach((currency, sums) -> otherCurrencies.add(new OtherCurrency(currency, money(sums[0]),
        monthly(sums[0], used), yearly(sums[0], used), money(sums[1]))));

    Map<Long, String> names = fixed.stream().collect(Collectors.toMap(Category::getId, Category::getName));
    return new Answer(EURO, months, used, from, to.minusDays(1), current.toString(), monthly, yearly(total, used),
        setAside(monthly), STEP, money(totalMonth), costs, upcoming(today, names, patterns, rows), otherCurrencies,
        uncategorized);
  }

  /** the monthly average rounded up to the next {@link #STEP} euro; 0 when nothing goes out */
  public static BigDecimal setAside(BigDecimal monthly) {
    if (monthly == null) {
      return null;
    }
    if (monthly.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal step = BigDecimal.valueOf(STEP);
    return monthly.divide(step, 0, RoundingMode.CEILING).multiply(step);
  }

  static List<Upcoming> upcoming(LocalDate today, Map<Long, String> names, Map<Long, String> patterns, List<Row> rows) {
    Map<List<Object>, List<Row>> groups = new LinkedHashMap<>();
    for (Row row : rows) {
      if (row.amount().signum() < 0) {
        List<Object> key = new ArrayList<>();
        key.add(row.categoryId());
        key.add(row.ruleId() == null ? -1L : row.ruleId());
        key.add(row.currency());
        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
      }
    }
    List<Upcoming> result = new ArrayList<>();
    for (List<Row> group : groups.values()) {
      Row any = group.getFirst();
      // one payment per day: two movements of the same day are one payment
      TreeMap<LocalDate, BigDecimal> payments = new TreeMap<>();
      group.forEach(r -> payments.merge(r.day(), r.amount().negate(), BigDecimal::add));
      List<LocalDate> days = new ArrayList<>(payments.keySet());
      Cadence cadence = cadence(days);
      List<BigDecimal> lastAmounts = new ArrayList<>(payments.values());
      BigDecimal amount = median(lastAmounts.subList(Math.max(0, lastAmounts.size() - LAST_PAYMENTS), lastAmounts.size()));
      LocalDate last = days.getLast();
      LocalDate expected = null;
      boolean late = false;
      if (cadence != Cadence.UNKNOWN) {
        expected = last.plusMonths(cadence.months);
        if (!today.isBefore(expected.plusMonths(cadence.months))) {
          continue; // a whole cadence late: it has stopped
        }
        late = expected.isBefore(today);
      }
      result.add(new Upcoming(any.categoryId(), names.get(any.categoryId()), any.ruleId(),
          any.ruleId() == null ? null : patterns.get(any.ruleId()), any.currency(), cadence, expected, amount, last,
          days.size(), late));
    }
    result.sort(Comparator.comparing((Upcoming u) -> u.expectedOn() == null ? 1 : 0)
        .thenComparing(u -> u.expectedOn() == null ? LocalDate.MAX : u.expectedOn())
        .thenComparing(Upcoming::last, Comparator.reverseOrder()));
    return result;
  }

  /** the cadence of payments on these days (oldest first), UNKNOWN when the gaps do not say one */
  public static Cadence cadence(List<LocalDate> days) {
    if (days.size() < 2) {
      return Cadence.UNKNOWN;
    }
    List<Long> gaps = new ArrayList<>();
    for (int i = 1; i < days.size(); i++) {
      gaps.add(ChronoUnit.DAYS.between(days.get(i - 1), days.get(i)));
    }
    gaps = gaps.subList(Math.max(0, gaps.size() - LAST_GAPS), gaps.size());
    List<Long> sorted = gaps.stream().sorted().toList();
    int n = sorted.size();
    double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    for (Cadence c : Cadence.values()) {
      if (c == Cadence.UNKNOWN || median < c.minDays || median > c.maxDays) {
        continue;
      }
      long inBand = gaps.stream().filter(c::fits).count();
      if (days.size() >= c.minPayments && inBand * 10 >= gaps.size() * 6L) {
        return c;
      }
      return Cadence.UNKNOWN;
    }
    return Cadence.UNKNOWN;
  }

  /** the middle amount (the mean of the two in the middle for an even count), to the cent */
  public static BigDecimal median(List<BigDecimal> values) {
    if (values.isEmpty()) {
      return null;
    }
    List<BigDecimal> sorted = values.stream().sorted().toList();
    int n = sorted.size();
    BigDecimal middle = n % 2 == 1 ? sorted.get(n / 2)
        : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    return money(middle);
  }

  private static BigDecimal monthly(BigDecimal spent, int months) {
    return months <= 0 ? null : spent.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal yearly(BigDecimal spent, int months) {
    return months <= 0 ? null
        : spent.multiply(BigDecimal.valueOf(12)).divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
