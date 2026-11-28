package com.rondinella.moneymanageapi.plan;

import com.rondinella.moneymanageapi.assets.Checks;
import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import com.rondinella.moneymanageapi.investments.Portfolio;
import com.rondinella.moneymanageapi.montecarlo.MonteCarloService;
import com.rondinella.moneymanageapi.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The plans of the investments: what they could be worth at a target date in three scenarios, saved in versions, and
 * how the real value goes against the plan in use. The arithmetic is in {@link Projection}; this class reads and saves.
 * <ul>
 *   <li>Inputs from money (today): the value of the investments, the money put in (cost of what is held, the base of
 *   the taxes), the monthly payment (purchases minus sales of the last 12 months, a month; fewer months when the first
 *   purchase is more recent; never below zero) and the yearly return so far (XIRR, for reference). Each of the first
 *   three can be the user's own value instead ({@code *Own}).</li>
 *   <li>Saving adds a version; the ones before stay. The newest is in use.</li>
 *   <li>Planned against real: the value of the investments at the end of every month from the month of the first
 *   version (taken on the first of the next month, or filled in from the history of the investments when missing; never
 *   changed afterwards), against the base scenario of the version from the day it was saved. Within
 *   {@link #ON_TRACK} of the planned value it is on track, otherwise above or below.</li>
 * </ul>
 * A refused request is a 400 with a code: NAME_REQUIRED, NAME_TOO_LONG (60), TARGET_DATE (from next month to 100 years),
 * START_RANGE, INVESTED_RANGE, MONTHLY_RANGE (0 or more, two decimals), RATE_RANGE (-20 to 30), RATE_ORDER (prudent <=
 * base <= optimistic), INFLATION_RANGE (-5 to 20), TAX_RANGE (0 to 100), TOO_MANY_EVENTS (50), EVENT_KIND, EVENT_DATE
 * (from next month to the target date), EVENT_AMOUNT (not 0, two decimals), EVENT_LABEL (60), TOO_MANY (20 plans),
 * TOO_MANY_VERSIONS (1000); an id or a version that is not there is a 404.
 */
@Service
public class Plans {

  private static final Logger log = LoggerFactory.getLogger(Plans.class);

  public static final String CURRENCY = Portfolio.CURRENCY;
  static final int MAX_NAME = 60;
  static final int MAX_LABEL = 60;
  static final int MAX_EVENTS = 50;
  static final int MAX_PLANS = 20;
  static final int MAX_VERSIONS = 1000;
  static final int MAX_YEARS = 100;
  /** within 5% of the planned value: on track */
  public static final double ON_TRACK = 0.05;
  static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
  static final BigDecimal MAX_MONTHLY = new BigDecimal("1000000000");
  static final BigDecimal DEFAULT_PRUDENT = new BigDecimal("3");
  static final BigDecimal DEFAULT_BASE = new BigDecimal("5");
  static final BigDecimal DEFAULT_OPTIMISTIC = new BigDecimal("7");
  static final BigDecimal DEFAULT_INFLATION = new BigDecimal("2");
  static final BigDecimal DEFAULT_TAX = new BigDecimal("26");
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** {@code kind}: ONE_OFF or MONTHLY; {@code amount}: positive in, negative out (MONTHLY: the change of the payment) */
  public record EventInput(String kind, LocalDate date, BigDecimal amount, String label) {
  }

  /**
   * What a plan says. Rates, inflation and tax in percent (5 = 5% a year; empty: 3, 5, 7, 2 and 26). {@code *Own}
   * false: on saving, money's value of today is taken instead of the one given.
   */
  public record Config(LocalDate targetDate, BigDecimal startValue, Boolean startValueOwn, BigDecimal invested,
                       Boolean investedOwn, BigDecimal monthly, Boolean monthlyOwn, BigDecimal inflation,
                       BigDecimal taxRate, BigDecimal ratePrudent, BigDecimal rateBase, BigDecimal rateOptimistic,
                       List<EventInput> events) {
  }

  public record PlanRequest(String name, Config config) {
  }

  public record ProjectionRequest(Config config, Boolean monteCarlo) {
  }

  /**
   * The inputs money gives, today: {@code value} of the investments, {@code invested} (cost of what is held),
   * {@code monthly} (net purchases a month over {@code monthlyMonths} months, at most 12), {@code historicalRate} (XIRR
   * in percent a year, null when it cannot be computed) since {@code since}, the first purchase.
   */
  public record MoneyInputs(LocalDate date, String currency, BigDecimal value, BigDecimal invested, BigDecimal monthly,
                            int monthlyMonths, BigDecimal historicalRate, LocalDate since) {
  }

  /** One scenario at the target date: before taxes, the taxes on the gain, after them, after them in euro of today. */
  public record Scenario(String key, BigDecimal rate, BigDecimal gross, BigDecimal invested, BigDecimal tax,
                         BigDecimal afterTax, BigDecimal real) {
  }

  /**
   * The band from the historical returns (see {@link MonteCarloService#band}); {@code available} false with
   * {@code reason} HISTORY_TOO_SHORT when there are fewer than 6 complete months. {@code yearlyMean}: exp(12 m) - 1,
   * {@code yearlyVolatility}: s x sqrt(12), both as fractions.
   */
  public record MonteCarlo(boolean available, String reason, int historyMonths, Double yearlyMean,
                           Double yearlyVolatility, int simulations, List<BigDecimal> p5, List<BigDecimal> p50,
                           List<BigDecimal> p95) {
  }

  /**
   * The plan month by month from {@code from} (index 0, the start) to the target month: {@code labels} yyyy-MM, the three
   * scenarios and the money put in of the base one; {@code years}: exact, for the euro of today.
   */
  public record ProjectionView(LocalDate from, LocalDate targetDate, int months, double years, String currency,
                               List<Scenario> scenarios, List<String> labels, List<BigDecimal> prudent,
                               List<BigDecimal> base, List<BigDecimal> optimistic, List<BigDecimal> invested,
                               MonteCarlo monteCarlo) {
  }

  /** the end of a month: the base scenario of the version and the real value (null when not there yet) */
  public record TrackPoint(String month, BigDecimal planned, BigDecimal actual) {
  }

  /**
   * Planned against real for one version. {@code status}: WAITING (no complete month since saving: the first one comes
   * on {@code nextCheck}), ON_TRACK, ABOVE, BELOW, compared at the end of {@code month}; {@code gap} = actual - planned,
   * {@code gapShare} = gap / planned.
   */
  public record Tracking(int version, LocalDate savedOn, String status, String month, BigDecimal planned,
                         BigDecimal actual, BigDecimal gap, Double gapShare, LocalDate nextCheck,
                         List<TrackPoint> points) {
  }

  public record VersionView(int number, Instant savedAt, LocalDate savedOn, Config config, BigDecimal moneyValue,
                            BigDecimal historicalRate) {
  }

  public record VersionSummary(int number, Instant savedAt, LocalDate savedOn, LocalDate targetDate,
                               BigDecimal monthly, boolean monthlyOwn, BigDecimal rateBase, int events) {
  }

  /**
   * One plan, at one version ({@code latest}: the one in use). {@code projection}: from today; for the version in use the
   * inputs from money are today's, for an older one the values it was saved with; null when the target date has passed.
   */
  public record PlanView(long id, String name, VersionView version, boolean latest, List<VersionSummary> versions,
                         MoneyInputs money, ProjectionView projection, Tracking tracking) {
  }

  public record PlanSummary(long id, String name, int versions, Instant savedAt, LocalDate targetDate,
                            Tracking tracking) {
  }

  /** every plan, the one saved last first, and the inputs from money (for a new plan) */
  public record Overview(MoneyInputs money, List<PlanSummary> plans) {
  }

  /** a config checked, with numbers */
  record Values(LocalDate target, BigDecimal start, boolean startOwn, BigDecimal invested, boolean investedOwn,
                BigDecimal monthly, boolean monthlyOwn, BigDecimal inflation, BigDecimal tax, BigDecimal prudent,
                BigDecimal base, BigDecimal optimistic, List<EventInput> events) {

    List<Projection.Event> projectionEvents() {
      return events.stream().map(e -> new Projection.Event(Projection.Kind.valueOf(e.kind()), e.date(),
          e.amount().doubleValue())).toList();
    }
  }

  private final PlanRepository plans;
  private final PlanVersionRepository versions;
  private final PlanEventRepository events;
  private final PlanSnapshotRepository snapshots;
  private final Portfolio portfolio;
  private final UserRepository users;
  private final EntityManager em;
  private final TransactionTemplate tx;
  private final Clock clock;

  public Plans(PlanRepository plans, PlanVersionRepository versions, PlanEventRepository events,
               PlanSnapshotRepository snapshots, Portfolio portfolio, UserRepository users, EntityManager em,
               PlatformTransactionManager transactions) {
    this.plans = plans;
    this.versions = versions;
    this.events = events;
    this.snapshots = snapshots;
    this.portfolio = portfolio;
    this.users = users;
    this.em = em;
    this.tx = new TransactionTemplate(transactions);
    this.clock = Clock.system(AccountPages.ZONE);
  }

  private LocalDate today() {
    return LocalDate.now(clock);
  }

  // ---- reading

  public Overview overview() {
    LocalDate today = today();
    Portfolio.Answer answer = portfolio.compute(today);
    List<PlanSummary> list = new ArrayList<>();
    for (Plan plan : plans.findAll()) {
      List<PlanVersion> all = versions.findByPlanIdOrderByNumberDesc(plan.getId());
      if (all.isEmpty()) {
        continue;
      }
      fillSnapshots(plan.getId(), all, answer.history(), today);
      PlanVersion latest = all.getFirst();
      list.add(new PlanSummary(plan.getId(), plan.getName(), all.size(), latest.getSavedAt(), latest.getTargetDate(),
          tracking(latest, snapshots.findByPlanIdOrderByMonthAsc(plan.getId()))));
    }
    list.sort(Comparator.comparing(PlanSummary::savedAt).reversed().thenComparing(PlanSummary::id));
    return new Overview(moneyInputs(answer, today), list);
  }

  /** one plan at {@code number} (null: the version in use) */
  public PlanView get(long id, Integer number) {
    LocalDate today = today();
    Plan plan = plans.findById(id).orElseThrow(NoSuchElementException::new);
    List<PlanVersion> all = versions.findByPlanIdOrderByNumberDesc(id);
    if (all.isEmpty()) {
      throw new NoSuchElementException();
    }
    PlanVersion shown = number == null ? all.getFirst()
        : all.stream().filter(v -> v.getNumber() == number).findFirst().orElseThrow(NoSuchElementException::new);
    boolean latest = shown.getNumber() == all.getFirst().getNumber();
    Portfolio.Answer answer = portfolio.compute(today);
    MoneyInputs money = moneyInputs(answer, today);
    fillSnapshots(id, all, answer.history(), today);
    Config config = config(shown);
    Config resolved = latest ? withMoney(config, money) : config;
    ProjectionView projection;
    try {
      projection = projection(values(resolved, today, false), today, null);
    } catch (org.springframework.web.server.ResponseStatusException e) {
      projection = null; // the target date has passed: the page asks for another one
    }
    List<VersionSummary> summaries = all.stream().map(v -> new VersionSummary(v.getNumber(), v.getSavedAt(),
        v.getSavedOn(), v.getTargetDate(), v.getMonthly(), v.isMonthlyOwn(), v.getRateBase(),
        events.findByVersionIdOrderByPositionAsc(v.getId()).size())).toList();
    return new PlanView(id, plan.getName(), view(shown, config), latest, summaries, money, projection,
        tracking(shown, snapshots.findByPlanIdOrderByMonthAsc(id)));
  }

  /**
   * The projection of a config as it is (the values given, also for the inputs from money), from today. Events
   * outside the plan (passed, or after the target) do not count. {@code monteCarlo}: also the band from the history.
   */
  public ProjectionView project(ProjectionRequest request) {
    LocalDate today = today();
    Values values = values(request == null ? null : request.config(), today, false);
    List<Double> returns = null;
    if (Boolean.TRUE.equals(request.monteCarlo())) {
      returns = MonteCarloService.marketReturns(portfolio.compute(today, false).history(), today);
    }
    return projection(values, today, returns);
  }

  // ---- writing

  public PlanView create(PlanRequest request) {
    if (plans.count() >= MAX_PLANS) {
      throw Checks.bad("TOO_MANY");
    }
    String name = name(request == null ? null : request.name());
    LocalDate today = today();
    Values values = values(request.config(), today, true);
    Portfolio.Answer answer = portfolio.compute(today);
    MoneyInputs money = moneyInputs(answer, today);
    Long id = tx.execute(status -> {
      Plan plan = new Plan();
      plan.setName(name);
      plan.setCreatedAt(clock.instant());
      plans.saveAndFlush(plan);
      saveVersion(plan.getId(), 1, values, money, clock.instant(), today);
      return plan.getId();
    });
    return get(id, null);
  }

  /** a new version (and the name), the ones before stay */
  public PlanView save(long id, PlanRequest request) {
    Plan plan = plans.findById(id).orElseThrow(NoSuchElementException::new);
    String name = name(request == null ? null : request.name());
    LocalDate today = today();
    Values values = values(request.config(), today, true);
    long count = versions.countByPlanId(id);
    if (count >= MAX_VERSIONS) {
      throw Checks.bad("TOO_MANY_VERSIONS");
    }
    Portfolio.Answer answer = portfolio.compute(today);
    MoneyInputs money = moneyInputs(answer, today);
    tx.executeWithoutResult(status -> {
      plan.setName(name);
      plans.saveAndFlush(plan);
      int next = versions.findFirstByPlanIdOrderByNumberDesc(id).map(PlanVersion::getNumber).orElse(0) + 1;
      saveVersion(id, next, values, money, clock.instant(), today);
    });
    return get(id, null);
  }

  /** the plan, its versions and its snapshots */
  public void delete(long id) {
    Plan plan = plans.findById(id).orElseThrow(NoSuchElementException::new);
    tx.executeWithoutResult(status -> {
      em.createQuery("DELETE FROM PlanEvent e WHERE e.versionId IN (SELECT v.id FROM PlanVersion v WHERE v.planId = :id)")
          .setParameter("id", id).executeUpdate();
      em.createQuery("DELETE FROM PlanVersion v WHERE v.planId = :id").setParameter("id", id).executeUpdate();
      em.createQuery("DELETE FROM PlanSnapshot s WHERE s.planId = :id").setParameter("id", id).executeUpdate();
      plans.delete(plan);
    });
  }

  private void saveVersion(Long planId, int number, Values values, MoneyInputs money, Instant savedAt,
                           LocalDate savedOn) {
    PlanVersion v = new PlanVersion();
    v.setPlanId(planId);
    v.setNumber(number);
    v.setSavedAt(savedAt);
    v.setSavedOn(savedOn);
    v.setTargetDate(values.target());
    v.setStartValue(values.startOwn() ? values.start() : money.value());
    v.setStartValueOwn(values.startOwn());
    v.setInvested(values.investedOwn() ? values.invested() : money.invested());
    v.setInvestedOwn(values.investedOwn());
    v.setMonthly(values.monthlyOwn() ? values.monthly() : money.monthly());
    v.setMonthlyOwn(values.monthlyOwn());
    v.setMoneyValue(money.value());
    v.setHistoricalRate(money.historicalRate());
    v.setInflation(values.inflation());
    v.setTaxRate(values.tax());
    v.setRatePrudent(values.prudent());
    v.setRateBase(values.base());
    v.setRateOptimistic(values.optimistic());
    versions.saveAndFlush(v);
    int position = 0;
    for (EventInput e : values.events()) {
      PlanEvent event = new PlanEvent();
      event.setVersionId(v.getId());
      event.setPosition(position++);
      event.setKind(Projection.Kind.valueOf(e.kind()));
      event.setOnDate(e.date());
      event.setAmount(e.amount());
      event.setLabel(e.label());
      events.save(event);
    }
    events.flush();
  }

  // ---- planned against real

  /**
   * Once a month (the first, early in the morning), for every user with a plan: the real value at the end of the month
   * before (and of any month missing since the first version). Idempotent: a month already there is never changed.
   */
  @Scheduled(cron = "${app.plans.cron:0 15 4 1 * *}", zone = "Europe/Rome")
  public void monthlySnapshots() {
    int done = 0;
    for (User user : users.findAll()) {
      String schema = TenantContext.schemaOf(user.getId());
      try {
        done += TenantContext.runAs(schema, this::snapshotAll);
      } catch (RuntimeException e) {
        log.error("Plans: monthly snapshot failed for a user ({})", e.getClass().getName());
      }
    }
    log.info("Plans: monthly snapshots, {} new", done);
  }

  /** every plan of the current user; how many months were added */
  int snapshotAll() {
    List<Plan> all = plans.findAll();
    if (all.isEmpty()) {
      return 0;
    }
    LocalDate today = today();
    Portfolio.Answer answer = portfolio.compute(today);
    int added = 0;
    for (Plan plan : all) {
      added += fillSnapshots(plan.getId(), versions.findByPlanIdOrderByNumberDesc(plan.getId()), answer.history(), today);
    }
    return added;
  }

  /**
   * The months missing from the month of the first version to the last complete one, from the history of the
   * investments (its value at the end of the month; 0 before the first purchase). Never twice, also when two requests
   * do it at the same time.
   */
  int fillSnapshots(Long planId, List<PlanVersion> all, List<Portfolio.Point> history, LocalDate today) {
    if (all.isEmpty()) {
      return 0;
    }
    YearMonth first = all.stream().map(v -> YearMonth.from(v.getSavedOn())).min(Comparator.naturalOrder()).orElseThrow();
    YearMonth last = YearMonth.from(today).minusMonths(1);
    if (first.isAfter(last)) {
      return 0;
    }
    Map<LocalDate, Boolean> have = new HashMap<>();
    snapshots.findByPlanIdOrderByMonthAsc(planId).forEach(s -> have.put(s.getMonth(), true));
    List<Object[]> rows = new ArrayList<>();
    for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
      if (have.containsKey(m.atDay(1))) {
        continue;
      }
      Portfolio.Point point = floor(history, m.atEndOfMonth());
      rows.add(new Object[]{m.atDay(1), point == null ? BigDecimal.ZERO.setScale(2) : point.value(),
          point == null ? BigDecimal.ZERO.setScale(2) : point.invested()});
    }
    if (rows.isEmpty()) {
      return 0;
    }
    Instant now = clock.instant();
    Integer added = tx.execute(status -> {
      int n = 0;
      for (Object[] row : rows) {
        n += em.createNativeQuery("INSERT INTO plan_snapshot (plan_id, month, value, invested, taken_at) "
                + "VALUES (?1, ?2, ?3, ?4, ?5) ON CONFLICT DO NOTHING")
            .setParameter(1, planId).setParameter(2, row[0]).setParameter(3, row[1]).setParameter(4, row[2])
            .setParameter(5, java.sql.Timestamp.from(now)).executeUpdate();
      }
      return n;
    });
    return added == null ? 0 : added;
  }

  /** the base scenario of {@code version} from the day it was saved, against the snapshots from that month on */
  static Tracking tracking(PlanVersion version, List<PlanEvent> versionEvents, List<PlanSnapshot> all) {
    YearMonth saved = YearMonth.from(version.getSavedOn());
    Projection.Schedule schedule = Projection.schedule(version.getSavedOn(), version.getTargetDate(),
        version.getStartValue().doubleValue(), version.getInvested().doubleValue(), version.getMonthly().doubleValue(),
        versionEvents.stream().map(e -> new Projection.Event(e.getKind(), e.getOnDate(), e.getAmount().doubleValue()))
            .toList());
    Projection.Path path = Projection.path(schedule, version.getRateBase().doubleValue() / 100);
    Map<YearMonth, BigDecimal> actual = new HashMap<>();
    YearMonth lastActual = null;
    for (PlanSnapshot s : all) {
      YearMonth m = YearMonth.from(s.getMonth());
      if (!m.isBefore(saved) && Projection.monthIndex(version.getSavedOn(), s.getMonth()) <= schedule.months()) {
        actual.put(m, s.getValue());
        lastActual = lastActual == null || m.isAfter(lastActual) ? m : lastActual;
      }
    }
    List<TrackPoint> points = new ArrayList<>();
    int lastK = lastActual == null ? 0 : (int) java.time.temporal.ChronoUnit.MONTHS.between(saved, lastActual);
    for (int k = 0; k <= lastK; k++) {
      YearMonth m = saved.plusMonths(k);
      points.add(new TrackPoint(m.toString(), money(path.value()[k]), actual.get(m)));
    }
    if (lastActual == null) {
      return new Tracking(version.getNumber(), version.getSavedOn(), "WAITING", null, null, null, null, null,
          saved.plusMonths(1).atDay(1), points);
    }
    BigDecimal planned = money(path.value()[lastK]);
    BigDecimal real = actual.get(lastActual);
    BigDecimal gap = real.subtract(planned);
    Double share = planned.signum() > 0 ? gap.divide(planned, 8, RoundingMode.HALF_UP).doubleValue() : null;
    String status = share == null ? (gap.signum() == 0 ? "ON_TRACK" : "ABOVE")
        : Math.abs(share) <= ON_TRACK ? "ON_TRACK" : share > 0 ? "ABOVE" : "BELOW";
    return new Tracking(version.getNumber(), version.getSavedOn(), status, lastActual.toString(), planned, real, gap,
        share, lastActual.plusMonths(2).atDay(1), points);
  }

  private Tracking tracking(PlanVersion version, List<PlanSnapshot> all) {
    return tracking(version, events.findByVersionIdOrderByPositionAsc(version.getId()), all);
  }

  // ---- the numbers

  static MoneyInputs moneyInputs(Portfolio.Answer answer, LocalDate today) {
    Portfolio.Performance p = answer.performance();
    int months = 0;
    BigDecimal monthly = BigDecimal.ZERO.setScale(2);
    if (p.since() != null) {
      months = p.since().isAfter(today.minusYears(1))
          ? Math.max(1, Projection.monthIndex(p.since(), today) + 1) : 12;
      BigDecimal average = p.netInLastYear().divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
      monthly = average.signum() > 0 ? average : BigDecimal.ZERO.setScale(2);
    }
    Double xirr = p.xirr();
    BigDecimal rate = xirr == null || !Double.isFinite(xirr) || Math.abs(xirr) > 99
        ? null : BigDecimal.valueOf(xirr * 100).setScale(2, RoundingMode.HALF_UP);
    return new MoneyInputs(today, CURRENCY, answer.totals().value(), answer.totals().invested(), monthly, months, rate,
        p.since());
  }

  ProjectionView projection(Values v, LocalDate today, List<Double> returns) {
    Projection.Schedule schedule = Projection.schedule(today, v.target(), v.start().doubleValue(),
        v.invested().doubleValue(), v.monthly().doubleValue(), v.projectionEvents());
    double years = Projection.years(today, v.target());
    double tax = v.tax().doubleValue() / 100;
    double inflation = v.inflation().doubleValue() / 100;
    List<Scenario> scenarios = new ArrayList<>();
    List<List<BigDecimal>> lines = new ArrayList<>();
    List<BigDecimal> invested = null;
    String[] keys = {"PRUDENT", "BASE", "OPTIMISTIC"};
    BigDecimal[] rates = {v.prudent(), v.base(), v.optimistic()};
    for (int i = 0; i < 3; i++) {
      Projection.Path path = Projection.path(schedule, rates[i].doubleValue() / 100);
      double gross = path.end();
      double paidTax = Projection.tax(gross, path.endInvested(), tax);
      double after = gross - paidTax;
      scenarios.add(new Scenario(keys[i], rates[i], money(gross), money(path.endInvested()), money(paidTax),
          money(after), money(after / Math.pow(1 + inflation, years))));
      lines.add(list(path.value()));
      if (i == 1) {
        invested = list(path.invested());
      }
    }
    List<String> labels = new ArrayList<>();
    for (int k = 0; k <= schedule.months(); k++) {
      labels.add(YearMonth.from(today).plusMonths(k).toString());
    }
    MonteCarlo mc = null;
    if (returns != null) {
      MonteCarloService.Band band = MonteCarloService.band(returns, schedule, MonteCarloService.PLAN_SIMULATIONS);
      mc = band == null
          ? new MonteCarlo(false, "HISTORY_TOO_SHORT", returns.size(), null, null, 0, null, null, null)
          : new MonteCarlo(true, null, band.historyMonths(), Math.exp(12 * band.monthlyMean()) - 1,
          band.monthlyStdDev() * Math.sqrt(12), band.simulations(), list(band.p5()), list(band.p50()), list(band.p95()));
    }
    return new ProjectionView(today, v.target(), schedule.months(), years, CURRENCY, scenarios, labels, lines.get(0),
        lines.get(1), lines.get(2), invested, mc);
  }

  /**
   * Checks a config. {@code saving}: events must be inside the plan (from next month to the target month); otherwise
   * they are only left out. The values from money must be there in any case (the caller fills them in).
   */
  static Values values(Config c, LocalDate today, boolean saving) {
    if (c == null || c.targetDate() == null) {
      throw Checks.bad("TARGET_DATE");
    }
    LocalDate target = c.targetDate();
    int months = Projection.monthIndex(today, target);
    if (months < 1 || target.isAfter(today.plusYears(MAX_YEARS))) {
      throw Checks.bad("TARGET_DATE");
    }
    boolean startOwn = Boolean.TRUE.equals(c.startValueOwn());
    boolean investedOwn = Boolean.TRUE.equals(c.investedOwn());
    boolean monthlyOwn = Boolean.TRUE.equals(c.monthlyOwn());
    BigDecimal start = amount(c.startValue(), MAX_AMOUNT, "START_RANGE", saving && !startOwn);
    BigDecimal invested = amount(c.invested(), MAX_AMOUNT, "INVESTED_RANGE", saving && !investedOwn);
    BigDecimal monthly = amount(c.monthly(), MAX_MONTHLY, "MONTHLY_RANGE", saving && !monthlyOwn);
    BigDecimal prudent = percent(c.ratePrudent(), DEFAULT_PRUDENT, -20, 30, "RATE_RANGE");
    BigDecimal base = percent(c.rateBase(), DEFAULT_BASE, -20, 30, "RATE_RANGE");
    BigDecimal optimistic = percent(c.rateOptimistic(), DEFAULT_OPTIMISTIC, -20, 30, "RATE_RANGE");
    if (prudent.compareTo(base) > 0 || base.compareTo(optimistic) > 0) {
      throw Checks.bad("RATE_ORDER");
    }
    BigDecimal inflation = percent(c.inflation(), DEFAULT_INFLATION, -5, 20, "INFLATION_RANGE");
    BigDecimal tax = percent(c.taxRate(), DEFAULT_TAX, 0, 100, "TAX_RANGE");
    List<EventInput> given = c.events() == null ? List.of() : c.events();
    if (given.size() > MAX_EVENTS) {
      throw Checks.bad("TOO_MANY_EVENTS");
    }
    List<EventInput> clean = new ArrayList<>();
    for (EventInput e : given) {
      if (e == null || e.kind() == null) {
        throw Checks.bad("EVENT_KIND");
      }
      String kind = e.kind().strip().toUpperCase(Locale.ROOT);
      if (!kind.equals("ONE_OFF") && !kind.equals("MONTHLY")) {
        throw Checks.bad("EVENT_KIND");
      }
      if (e.date() == null) {
        throw Checks.bad("EVENT_DATE");
      }
      int k = Projection.monthIndex(today, e.date());
      if (saving && (k < 1 || k > months)) {
        throw Checks.bad("EVENT_DATE");
      }
      BigDecimal max = kind.equals("MONTHLY") ? MAX_MONTHLY : MAX_AMOUNT;
      if (e.amount() == null || e.amount().signum() == 0 || e.amount().abs().compareTo(max) > 0
          || e.amount().stripTrailingZeros().scale() > 2) {
        throw Checks.bad("EVENT_AMOUNT");
      }
      String label = e.label() == null ? "" : e.label().strip().replaceAll("\\s+", " ");
      if (label.length() > MAX_LABEL) {
        throw Checks.bad("EVENT_LABEL");
      }
      clean.add(new EventInput(kind, e.date(), e.amount(), label.isEmpty() ? null : label));
    }
    return new Values(target, start, startOwn, invested, investedOwn, monthly, monthlyOwn, inflation, tax, prudent, base,
        optimistic, clean);
  }

  /** 0 or more, two decimals, at most {@code max}; {@code fromMoney}: not checked, money's value is taken */
  private static BigDecimal amount(BigDecimal value, BigDecimal max, String code, boolean fromMoney) {
    if (fromMoney) {
      return value == null ? BigDecimal.ZERO : value;
    }
    if (value == null || value.signum() < 0 || value.compareTo(max) > 0 || value.stripTrailingZeros().scale() > 2) {
      throw Checks.bad(code);
    }
    return value;
  }

  private static BigDecimal percent(BigDecimal value, BigDecimal fallback, int min, int max, String code) {
    if (value == null) {
      return fallback;
    }
    if (value.compareTo(BigDecimal.valueOf(min)) < 0 || value.compareTo(BigDecimal.valueOf(max)) > 0
        || value.stripTrailingZeros().scale() > 2) {
      throw Checks.bad(code);
    }
    return value;
  }

  static String name(String name) {
    String clean = name == null ? "" : name.strip().replaceAll("\\s+", " ");
    if (clean.isEmpty()) {
      throw Checks.bad("NAME_REQUIRED");
    }
    if (clean.length() > MAX_NAME) {
      throw Checks.bad("NAME_TOO_LONG");
    }
    return clean;
  }

  private Config config(PlanVersion v) {
    List<EventInput> list = events.findByVersionIdOrderByPositionAsc(v.getId()).stream()
        .map(e -> new EventInput(e.getKind().name(), e.getOnDate(), e.getAmount(), e.getLabel())).toList();
    return new Config(v.getTargetDate(), v.getStartValue(), v.isStartValueOwn(), v.getInvested(), v.isInvestedOwn(),
        v.getMonthly(), v.isMonthlyOwn(), v.getInflation(), v.getTaxRate(), v.getRatePrudent(), v.getRateBase(),
        v.getRateOptimistic(), list);
  }

  /** the inputs from money replaced by today's */
  private static Config withMoney(Config c, MoneyInputs m) {
    return new Config(c.targetDate(), c.startValueOwn() ? c.startValue() : m.value(), c.startValueOwn(),
        c.investedOwn() ? c.invested() : m.invested(), c.investedOwn(), c.monthlyOwn() ? c.monthly() : m.monthly(),
        c.monthlyOwn(), c.inflation(), c.taxRate(), c.ratePrudent(), c.rateBase(), c.rateOptimistic(), c.events());
  }

  private static VersionView view(PlanVersion v, Config config) {
    return new VersionView(v.getNumber(), v.getSavedAt(), v.getSavedOn(), config, v.getMoneyValue(),
        v.getHistoricalRate());
  }

  private static Portfolio.Point floor(List<Portfolio.Point> history, LocalDate day) {
    Portfolio.Point found = null;
    for (Portfolio.Point p : history) {
      if (!p.date().isAfter(day)) {
        found = p;
      }
    }
    return found;
  }

  private static List<BigDecimal> list(double[] values) {
    List<BigDecimal> out = new ArrayList<>(values.length);
    for (double v : values) {
      out.add(money(v));
    }
    return out;
  }

  static BigDecimal money(double value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
  }

  // ---- sample data

  /**
   * A plan of the sample data, "Indipendenza (esempio)", with two versions saved 12 and 5 months ago from the history of
   * the sample investments (the first: 300 a month, nothing else; the second: 400 a month, a car in two years, the
   * payment up by 200 when the loan ends), so the page shows planned against real at once.
   */
  public void sample(LocalDate today) {
    Portfolio.Answer answer = portfolio.compute(today, false);
    LocalDate target = LocalDate.of(today.getYear() + 19, 6, 30);
    tx.executeWithoutResult(status -> {
      Plan plan = new Plan();
      plan.setName("Indipendenza (esempio)");
      LocalDate first = today.minusMonths(12);
      LocalDate second = today.minusMonths(5);
      ZoneId zone = AccountPages.ZONE;
      plan.setCreatedAt(first.atTime(20, 0).atZone(zone).toInstant());
      plans.saveAndFlush(plan);
      saveVersion(plan.getId(), 1, sampleValues(target, "300", List.of()), sampleMoney(answer, first),
          first.atTime(20, 0).atZone(zone).toInstant(), first);
      List<EventInput> list = List.of(
          new EventInput("ONE_OFF", LocalDate.of(today.getYear() + 2, 9, 1), new BigDecimal("-15000"), "Auto nuova (esempio)"),
          new EventInput("MONTHLY", LocalDate.of(today.getYear() + 3, 1, 1), new BigDecimal("200"), "Fine prestito (esempio)"));
      saveVersion(plan.getId(), 2, sampleValues(target, "400", list), sampleMoney(answer, second),
          second.atTime(21, 0).atZone(zone).toInstant(), second);
    });
  }

  private static Values sampleValues(LocalDate target, String monthly, List<EventInput> list) {
    return new Values(target, null, false, null, false, new BigDecimal(monthly), true, DEFAULT_INFLATION, DEFAULT_TAX,
        DEFAULT_PRUDENT, DEFAULT_BASE, DEFAULT_OPTIMISTIC, list);
  }

  /** what money would have said on {@code day}: the value and the money put in from the history */
  private static MoneyInputs sampleMoney(Portfolio.Answer answer, LocalDate day) {
    Portfolio.Point point = floor(answer.history(), day);
    BigDecimal value = point == null ? BigDecimal.ZERO.setScale(2) : point.value();
    BigDecimal invested = point == null ? BigDecimal.ZERO.setScale(2) : point.invested();
    return new MoneyInputs(day, CURRENCY, value, invested, BigDecimal.ZERO, 0, null, null);
  }
}
