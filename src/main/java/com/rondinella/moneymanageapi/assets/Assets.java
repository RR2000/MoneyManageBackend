package com.rondinella.moneymanageapi.assets;

import com.rondinella.moneymanageapi.realestate.RealEstate;
import com.rondinella.moneymanageapi.realestate.RealEstateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The assets page: real estate, other assets (a car, gold, money in another currency, a loan given) and debts, with
 * their totals today by currency (never converted). The rules of what counts on a day are the same as in the net
 * worth: real estate from its purchase date on, another asset from the day of its estimate on (on every day when the
 * day is not known), a debt with what is left today on every day (its past is not rebuilt).
 */
@Service
public class Assets {

  public static final String EURO = "EUR";
  /** car or motorbike, gold and precious metals, cash or an account in another currency, money lent, anything else */
  public static final Set<String> KINDS = Set.of("VEHICLE", "GOLD", "CASH", "RECEIVABLE", "OTHER");
  /** of each type, per user */
  static final int MAX_ITEMS = 500;
  /** an estimate of the end further than this is "never" */
  static final int MAX_MONTHS = 1200;

  public record OtherAssetRequest(String name, String kind, BigDecimal value, String currency, LocalDate valuedOn) {
  }

  public record DebtRequest(String name, BigDecimal balance, String currency, BigDecimal annualRate,
                            BigDecimal monthlyPayment, LocalDate endDate) {
  }

  public record OtherAssetView(Long id, String name, String kind, BigDecimal value, String currency, LocalDate valuedOn) {
  }

  /**
   * How a debt ends: PAID (nothing left), END_DATE (the date given), ESTIMATE (from payment and rate), NEVER (the
   * payment does not cover the interest), UNKNOWN (no date and no payment). {@code monthsLeft}: from today to
   * {@code expectedEnd}, rounded up (negative when the end date has passed).
   */
  public record Payoff(String kind, LocalDate expectedEnd, Integer monthsLeft) {
  }

  public record DebtView(Long id, String name, BigDecimal balance, String currency, BigDecimal annualRate,
                         BigDecimal monthlyPayment, LocalDate endDate, Payoff payoff) {
  }

  /** what counts today in one currency; debts are negative, net = real estate + other assets + debts */
  public record Total(String currency, BigDecimal realEstate, BigDecimal otherAssets, BigDecimal debts, BigDecimal net) {
  }

  /** {@code totals}: euro first (always there), then the other currencies in alphabetical order */
  public record Answer(String currency, LocalDate today, List<RealEstate> realEstates, List<OtherAssetView> otherAssets,
                       List<DebtView> debts, List<Total> totals) {
  }

  private final RealEstateRepository realEstates;
  private final OtherAssetRepository others;
  private final DebtRepository debts;

  public Assets(RealEstateRepository realEstates, OtherAssetRepository others, DebtRepository debts) {
    this.realEstates = realEstates;
    this.others = others;
    this.debts = debts;
  }

  @Transactional(readOnly = true)
  public Answer summary(LocalDate today) {
    List<RealEstate> estates = realEstates.findAll().stream()
        .sorted(byValue(RealEstate::getCurrentEstimatedValue).thenComparing(RealEstate::getId)).toList();
    List<OtherAsset> owned = others.findAll().stream()
        .sorted(byValue(OtherAsset::getEstimatedValue).thenComparing(OtherAsset::getId)).toList();
    List<Debt> owed = debts.findAll().stream()
        .sorted(byValue(Debt::getBalance).thenComparing(Debt::getId)).toList();

    Map<String, BigDecimal[]> sums = new TreeMap<>();
    sums.put(EURO, zeros());
    for (RealEstate estate : estates) {
      if (counts(estate, today)) {
        add(sums, currencyOf(estate.getCurrency()), 0, estate.getCurrentEstimatedValue());
      }
    }
    for (OtherAsset asset : owned) {
      if (counts(asset, today)) {
        add(sums, currencyOf(asset.getCurrency()), 1, asset.getEstimatedValue());
      }
    }
    for (Debt debt : owed) {
      add(sums, currencyOf(debt.getCurrency()), 2, debt.getBalance().negate());
    }
    List<Total> totals = new ArrayList<>();
    totals.add(total(EURO, sums.get(EURO)));
    sums.forEach((currency, s) -> {
      if (!EURO.equals(currency)) {
        totals.add(total(currency, s));
      }
    });
    return new Answer(EURO, today, estates,
        owned.stream().map(Assets::view).toList(),
        owed.stream().map(d -> view(d, today)).toList(),
        totals);
  }

  @Transactional
  public OtherAssetView create(OtherAssetRequest request, LocalDate today) {
    if (others.count() >= MAX_ITEMS) {
      throw Checks.bad("TOO_MANY");
    }
    OtherAsset asset = new OtherAsset();
    fill(asset, request, today);
    return view(others.save(asset));
  }

  @Transactional
  public OtherAssetView update(long id, OtherAssetRequest request, LocalDate today) {
    OtherAsset asset = others.findById(id).orElseThrow(NoSuchElementException::new);
    fill(asset, request, today);
    return view(others.save(asset));
  }

  @Transactional
  public void deleteOther(long id) {
    others.delete(others.findById(id).orElseThrow(NoSuchElementException::new));
  }

  @Transactional
  public DebtView create(DebtRequest request, LocalDate today) {
    if (debts.count() >= MAX_ITEMS) {
      throw Checks.bad("TOO_MANY");
    }
    Debt debt = new Debt();
    fill(debt, request, today);
    return view(debts.save(debt), today);
  }

  @Transactional
  public DebtView update(long id, DebtRequest request, LocalDate today) {
    Debt debt = debts.findById(id).orElseThrow(NoSuchElementException::new);
    fill(debt, request, today);
    return view(debts.save(debt), today);
  }

  @Transactional
  public void deleteDebt(long id) {
    debts.delete(debts.findById(id).orElseThrow(NoSuchElementException::new));
  }

  private static void fill(OtherAsset asset, OtherAssetRequest request, LocalDate today) {
    if (request == null) {
      throw Checks.bad("NAME_REQUIRED");
    }
    asset.setName(Checks.name(request.name()));
    if (request.kind() == null || !KINDS.contains(request.kind())) {
      throw Checks.bad("KIND");
    }
    asset.setKind(request.kind());
    asset.setEstimatedValue(Checks.amount(request.value()));
    asset.setCurrency(Checks.currency(request.currency()));
    asset.setValuedOn(Checks.past(request.valuedOn(), today));
  }

  private static void fill(Debt debt, DebtRequest request, LocalDate today) {
    if (request == null) {
      throw Checks.bad("NAME_REQUIRED");
    }
    debt.setName(Checks.name(request.name()));
    debt.setBalance(Checks.amount(request.balance()));
    debt.setCurrency(Checks.currency(request.currency()));
    debt.setAnnualRate(Checks.rate(request.annualRate()));
    debt.setMonthlyPayment(Checks.optionalAmount(request.monthlyPayment()));
    debt.setEndDate(Checks.date(request.endDate(), today));
  }

  /** real estate is in the total from its purchase date on (on every day without a date), when it has a value */
  public static boolean counts(RealEstate estate, LocalDate day) {
    return estate.getCurrentEstimatedValue() != null
        && (estate.getPurchaseDate() == null || !estate.getPurchaseDate().isAfter(day));
  }

  /** another asset is in the total from the day of its estimate on (on every day without a date) */
  public static boolean counts(OtherAsset asset, LocalDate day) {
    return asset.getEstimatedValue() != null && (asset.getValuedOn() == null || !asset.getValuedOn().isAfter(day));
  }

  /** the currency of a row, upper case; none written (old real estate rows) is euro */
  public static String currencyOf(String currency) {
    return currency == null || currency.isBlank() ? EURO : currency.strip().toUpperCase(Locale.ROOT);
  }

  /**
   * When a debt is paid off. With an end date, that date. Without one, from the monthly payment P and the monthly rate
   * r (yearly rate / 12) on what is left B: n = ceil(B / P) without interest, otherwise
   * n = ceil(-ln(1 - r B / P) / ln(1 + r)) months (the length of an amortizing loan), never when P <= r B.
   */
  public static Payoff payoff(BigDecimal balance, BigDecimal annualRate, BigDecimal monthlyPayment, LocalDate endDate,
                              LocalDate today) {
    if (balance == null || balance.signum() == 0) {
      return new Payoff("PAID", null, null);
    }
    if (endDate != null) {
      return new Payoff("END_DATE", endDate, monthsBetween(today, endDate));
    }
    if (monthlyPayment == null || monthlyPayment.signum() == 0) {
      return new Payoff("UNKNOWN", null, null);
    }
    double b = balance.doubleValue();
    double p = monthlyPayment.doubleValue();
    double r = annualRate == null ? 0 : annualRate.doubleValue() / 100 / 12;
    double months;
    if (r == 0) {
      months = b / p;
    } else if (p <= r * b) {
      return new Payoff("NEVER", null, null);
    } else {
      months = -Math.log(1 - r * b / p) / Math.log(1 + r);
    }
    // a hair over a whole month is rounding, not one more payment
    int n = (int) Math.ceil(months - 1e-9);
    if (n > MAX_MONTHS) {
      return new Payoff("NEVER", null, null);
    }
    return new Payoff("ESTIMATE", today.plusMonths(n), n);
  }

  /** whole months from {@code from} to {@code to}, rounded up; negative when {@code to} is before */
  static int monthsBetween(LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      return -monthsBetween(to, from);
    }
    long months = ChronoUnit.MONTHS.between(from, to);
    return (int) (from.plusMonths(months).isBefore(to) ? months + 1 : months);
  }

  private static OtherAssetView view(OtherAsset a) {
    return new OtherAssetView(a.getId(), a.getName(), a.getKind(), a.getEstimatedValue(), a.getCurrency(),
        a.getValuedOn());
  }

  private static DebtView view(Debt d, LocalDate today) {
    return new DebtView(d.getId(), d.getName(), d.getBalance(), d.getCurrency(), d.getAnnualRate(),
        d.getMonthlyPayment(), d.getEndDate(),
        payoff(d.getBalance(), d.getAnnualRate(), d.getMonthlyPayment(), d.getEndDate(), today));
  }

  /** largest first, rows without a value last */
  private static <T> Comparator<T> byValue(Function<T, BigDecimal> value) {
    return Comparator.comparing(value, Comparator.nullsLast(Comparator.reverseOrder()));
  }

  private static BigDecimal[] zeros() {
    return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
  }

  private static void add(Map<String, BigDecimal[]> sums, String currency, int part, BigDecimal amount) {
    BigDecimal[] s = sums.computeIfAbsent(currency, c -> zeros());
    s[part] = s[part].add(amount);
  }

  private static Total total(String currency, BigDecimal[] s) {
    return new Total(currency, s[0], s[1], s[2], s[0].add(s[1]).add(s[2]));
  }
}
