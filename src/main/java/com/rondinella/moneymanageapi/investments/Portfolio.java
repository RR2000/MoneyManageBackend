package com.rondinella.moneymanageapi.investments;

import com.rondinella.moneymanageapi.brokertransactions.BrokerTransaction;
import com.rondinella.moneymanageapi.brokertransactions.BrokerTransactionRepository;
import com.rondinella.moneymanageapi.networth.NetWorth;
import com.rondinella.moneymanageapi.stocks.Instruments;
import com.rondinella.moneymanageapi.stocks.Stock;
import com.rondinella.moneymanageapi.stocks.StockPrices;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The investments page: every position (ISIN) with its average cost, what it is worth at its last price and the gain
 * before and after taxes; the returns from the real movements; the allocation against the targets of the user; the
 * worth and the money put in over time.
 * <ul>
 *   <li>Average cost: weighted average cost. A purchase adds its quantity and its cost (quantity times price plus the
 *   fee); a sale takes out the average cost of the quantity sold, and the difference with what it gave (net of the
 *   fee) is the realised gain.</li>
 *   <li>Taxes: {@link #TAX_RATE} of the gain, only when it is positive, position by position (an estimate: in Italy the
 *   losses of a fund do not offset the gains of another).</li>
 *   <li>Returns: see {@link Returns}. The money-weighted one (XIRR) takes every purchase and sale with its fees as a
 *   flow and today's worth as the last one; the time-weighted one chains the months.</li>
 *   <li>Only euro: a position whose trades or prices are in another currency is flagged and left out of totals,
 *   returns, allocation and history (nothing is converted); so is one without any price.</li>
 * </ul>
 */
@Service
public class Portfolio {

  public static final String CURRENCY = "EUR";
  public static final BigDecimal TAX_RATE = new BigDecimal("0.26");
  static final int MAX_TARGETS = 200;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /**
   * One ISIN. {@code invested}: the cost of the quantity held (with the fees); {@code gain} = value - invested;
   * {@code realized}: the gain already taken with sales. {@code excluded}: why it is not in the totals (CURRENCY,
   * NO_PRICE), null when it is.
   */
  public record Position(String isin, String name, String currency, boolean inTotal, String excluded,
                         BigDecimal quantity, BigDecimal averagePrice, BigDecimal invested, BigDecimal lastPrice,
                         LocalDate lastDate, BigDecimal value, BigDecimal gain, Double gainShare, BigDecimal tax,
                         BigDecimal netGain, BigDecimal realized, Double portfolioShare, BigDecimal targetPercent) {
  }

  /** The positions in the total. {@code bought}: every purchase with its fee; {@code sold}: every sale net of it. */
  public record Totals(BigDecimal invested, BigDecimal value, BigDecimal gain, Double gainShare, BigDecimal tax,
                       BigDecimal netGain, BigDecimal realized, BigDecimal fees, BigDecimal bought, BigDecimal sold) {
  }

  /**
   * {@code xirr}: money-weighted, a year; {@code simple}: (value + sold - bought) / bought; {@code twr}:
   * time-weighted, the whole period, and {@code twrYearly} the same a year. {@code days}: from the first purchase.
   */
  public record Performance(LocalDate since, long days, Double xirr, BigDecimal simpleGain, Double simple, Double twr,
                            Double twrYearly) {
  }

  /** One ISIN against its target: percent of the portfolio now and wanted; {@code gap} > 0: buy about that much. */
  public record Slice(String isin, String name, BigDecimal value, BigDecimal actualPercent, BigDecimal targetPercent,
                      BigDecimal gap) {
  }

  public record Allocation(List<Slice> slices, BigDecimal assignedPercent, BigDecimal unassignedPercent) {
  }

  /** The end of a month (or today): worth, money put in (purchases minus sales) and worth by ISIN. */
  public record Point(LocalDate date, BigDecimal value, BigDecimal invested, Map<String, BigDecimal> byIsin) {
  }

  /** Positions left out of the total: how many, their currencies and how many have no price. */
  public record Excluded(int instruments, List<String> currencies, int withoutPrice) {
  }

  public record Answer(String currency, LocalDate today, BigDecimal taxRate, List<Position> positions, int closed,
                       Totals totals, Performance performance, Allocation allocation, List<Point> history,
                       Excluded excluded) {
  }

  public record Target(String isin, BigDecimal percent) {
  }

  public record Targets(List<Target> targets, BigDecimal unassignedPercent) {
  }

  /** What the trades of one ISIN add up to, and how they went day by day. */
  private static final class Book {
    BigDecimal quantity = BigDecimal.ZERO;
    BigDecimal cost = BigDecimal.ZERO;
    BigDecimal realized = BigDecimal.ZERO;
    BigDecimal fees = BigDecimal.ZERO;
    BigDecimal bought = BigDecimal.ZERO;
    BigDecimal sold = BigDecimal.ZERO;
    BigDecimal netIn = BigDecimal.ZERO;
    String currency = CURRENCY;
    final List<Returns.Flow> flows = new ArrayList<>();
    /** at the end of each day with trades */
    final NavigableMap<LocalDate, BigDecimal> quantityByDay = new TreeMap<>();
    final NavigableMap<LocalDate, BigDecimal> netInByDay = new TreeMap<>();
    final NavigableMap<LocalDate, BigDecimal> tradePrice = new TreeMap<>();

    boolean held() {
      return quantity.signum() > 0;
    }
  }

  private final BrokerTransactionRepository transactions;
  private final Instruments instruments;
  private final StockPrices prices;
  private final AllocationTargetRepository targets;

  public Portfolio(BrokerTransactionRepository transactions, Instruments instruments, StockPrices prices,
                   AllocationTargetRepository targets) {
    this.transactions = transactions;
    this.instruments = instruments;
    this.prices = prices;
    this.targets = targets;
  }

  public Answer compute(LocalDate today) {
    List<String> isins = transactions.findDistinctIsin();
    prices.refresh(isins);
    Map<String, BigDecimal> wanted = targetMap();

    Map<String, Book> books = new LinkedHashMap<>();
    Map<String, Instruments.View> views = new HashMap<>();
    List<Position> positions = new ArrayList<>();
    Set<String> otherCurrencies = new TreeSet<>();
    int excludedCount = 0;
    int withoutPrice = 0;
    int closed = 0;
    for (String isin : isins) {
      Book book = book(transactions.findByIsinOrderByDatetime(isin));
      Instruments.View view = instruments.view(isin);
      String quote = view.quoteCurrency();
      String currency = !CURRENCY.equals(book.currency) ? book.currency
          : quote != null && !CURRENCY.equalsIgnoreCase(quote) ? quote.toUpperCase() : CURRENCY;
      boolean priced = view.lastPrice() != null;
      String excluded = !CURRENCY.equals(currency) ? "CURRENCY" : book.held() && !priced ? "NO_PRICE" : null;
      if (excluded == null) {
        books.put(isin, book);
        views.put(isin, view);
      }
      if (!book.held()) {
        closed++;
        continue;
      }
      if (excluded != null) {
        excludedCount++;
        if ("CURRENCY".equals(excluded)) {
          otherCurrencies.add(currency);
        } else {
          withoutPrice++;
        }
      }
      BigDecimal value = priced ? money(book.quantity.multiply(view.lastPrice())) : null;
      BigDecimal invested = money(book.cost);
      BigDecimal gain = value == null ? null : value.subtract(invested);
      BigDecimal tax = gain == null ? null : tax(gain);
      positions.add(new Position(isin, view.name(), currency, excluded == null, excluded, book.quantity.stripTrailingZeros(),
          book.cost.divide(book.quantity, 4, RoundingMode.HALF_UP), invested, view.lastPrice(), view.lastDate(), value,
          gain, gain == null ? null : share(gain, invested), tax, gain == null ? null : gain.subtract(tax),
          money(book.realized), null, wanted.get(isin)));
    }

    // totals of the positions in euro with a price
    BigDecimal invested = BigDecimal.ZERO;
    BigDecimal value = BigDecimal.ZERO;
    BigDecimal gain = BigDecimal.ZERO;
    BigDecimal tax = BigDecimal.ZERO;
    for (Position p : positions) {
      if (p.inTotal()) {
        invested = invested.add(p.invested());
        value = value.add(p.value());
        gain = gain.add(p.gain());
        tax = tax.add(p.tax());
      }
    }
    BigDecimal realized = BigDecimal.ZERO;
    BigDecimal fees = BigDecimal.ZERO;
    BigDecimal bought = BigDecimal.ZERO;
    BigDecimal sold = BigDecimal.ZERO;
    for (Book book : books.values()) {
      realized = realized.add(book.realized);
      fees = fees.add(book.fees);
      bought = bought.add(book.bought);
      sold = sold.add(book.sold);
    }
    Totals totals = new Totals(invested, value, gain, share(gain, invested), tax, gain.subtract(tax), money(realized),
        money(fees), money(bought), money(sold));

    BigDecimal total = value;
    List<Position> ordered = positions.stream()
        .map(p -> p.inTotal() ? withShare(p, share(p.value(), total)) : p)
        .sorted(Comparator.comparing(Position::inTotal).reversed()
            .thenComparing(p -> p.value() == null ? BigDecimal.ZERO : p.value(), Comparator.reverseOrder()))
        .toList();

    List<Point> history = new ArrayList<>();
    Performance performance = performance(books, views, totals, today, history);
    Allocation allocation = allocation(ordered, books, views, wanted, total);
    return new Answer(CURRENCY, today, TAX_RATE, ordered, closed, totals, performance, allocation, history,
        new Excluded(excludedCount, List.copyOf(otherCurrencies), withoutPrice));
  }

  /** The trades of one ISIN, oldest first, by weighted average cost. */
  private static Book book(List<BrokerTransaction> trades) {
    Book book = new Book();
    for (BrokerTransaction t : trades) {
      BigDecimal q = t.getQuantity();
      BigDecimal price = t.getStockPrice();
      if (q == null || price == null || q.signum() == 0) {
        continue;
      }
      if (t.getCurrency() != null && !CURRENCY.equalsIgnoreCase(t.getCurrency())) {
        book.currency = t.getCurrency().toUpperCase();
      }
      // a fee in another currency cannot be added: left out (Degiro charges in the currency of the account)
      BigDecimal fee = t.getFee() == null || (t.getFeeCurrency() != null && !CURRENCY.equalsIgnoreCase(t.getFeeCurrency()))
          ? BigDecimal.ZERO : t.getFee().abs();
      BigDecimal cash;
      if (q.signum() > 0) {
        BigDecimal cost = q.multiply(price).add(fee);
        book.cost = book.cost.add(cost);
        book.quantity = book.quantity.add(q);
        book.bought = book.bought.add(cost);
        cash = cost.negate();
      } else {
        BigDecimal sold = q.negate();
        BigDecimal basis = !book.held() ? BigDecimal.ZERO
            : sold.compareTo(book.quantity) >= 0 ? book.cost
            : book.cost.multiply(sold).divide(book.quantity, 10, RoundingMode.HALF_UP);
        BigDecimal proceeds = sold.multiply(price).subtract(fee);
        book.cost = book.cost.subtract(basis);
        book.quantity = book.quantity.subtract(sold);
        if (!book.held()) {
          book.cost = BigDecimal.ZERO;
        }
        book.realized = book.realized.add(proceeds.subtract(basis));
        book.sold = book.sold.add(proceeds);
        cash = proceeds;
      }
      book.fees = book.fees.add(fee);
      book.netIn = book.netIn.subtract(cash);
      LocalDate day = t.getDatetime().toLocalDateTime().toLocalDate();
      book.flows.add(new Returns.Flow(day, cash.doubleValue()));
      book.quantityByDay.put(day, book.quantity);
      book.netInByDay.put(day, book.netIn);
      book.tradePrice.put(day, price);
    }
    return book;
  }

  /**
   * Returns from the first trade to today, and the history (end of every month and today) they are computed on.
   */
  private Performance performance(Map<String, Book> books, Map<String, Instruments.View> views, Totals totals,
                                  LocalDate today, List<Point> history) {
    List<Returns.Flow> flows = new ArrayList<>();
    books.values().forEach(b -> flows.addAll(b.flows));
    if (flows.isEmpty()) {
      return new Performance(null, 0, null, BigDecimal.ZERO, null, null, null);
    }
    LocalDate since = flows.stream().map(Returns.Flow::date).min(LocalDate::compareTo).orElseThrow();
    if (since.isAfter(today)) {
      since = today;
    }

    // prices of each ISIN by day, from a year before the first trade (the last one before a day is its price)
    Timestamp from = Timestamp.valueOf(since.minusYears(1).atStartOfDay());
    Timestamp to = Timestamp.valueOf(today.plusDays(1).atStartOfDay());
    Map<String, NavigableMap<LocalDate, BigDecimal>> priceByDay = new HashMap<>();
    for (String isin : books.keySet()) {
      NavigableMap<LocalDate, BigDecimal> byDay = new TreeMap<>();
      for (Stock s : prices.between(isin, from, to)) {
        if (s.getClose() != null) {
          byDay.put(s.getDatetime().toLocalDateTime().toLocalDate(), s.getClose());
        }
      }
      priceByDay.put(isin, byDay);
    }

    NavigableMap<LocalDate, Double> valuations = new TreeMap<>();
    for (LocalDate day : NetWorth.days(since, today)) {
      BigDecimal worth = BigDecimal.ZERO;
      BigDecimal netIn = BigDecimal.ZERO;
      Map<String, BigDecimal> byIsin = new LinkedHashMap<>();
      for (Map.Entry<String, Book> entry : books.entrySet()) {
        Book book = entry.getValue();
        BigDecimal quantity = floor(book.quantityByDay, day);
        netIn = netIn.add(floor(book.netInByDay, day));
        if (quantity.signum() <= 0) {
          continue;
        }
        BigDecimal price = day.equals(today) && views.get(entry.getKey()).lastPrice() != null
            ? views.get(entry.getKey()).lastPrice() : floor(priceByDay.get(entry.getKey()), day);
        if (price.signum() == 0) {
          price = floor(book.tradePrice, day); // no price yet that day: the price paid
        }
        BigDecimal v = money(quantity.multiply(price));
        byIsin.put(entry.getKey(), v);
        worth = worth.add(v);
      }
      history.add(new Point(day, worth, money(netIn), byIsin));
      valuations.put(day, worth.doubleValue());
    }

    LocalDate start = since.minusDays(1);
    long days = ChronoUnit.DAYS.between(since, today);
    List<Returns.Flow> withToday = new ArrayList<>(flows);
    if (totals.value().signum() > 0) {
      withToday.add(new Returns.Flow(today, totals.value().doubleValue()));
    }
    BigDecimal simpleGain = totals.value().add(totals.sold()).subtract(totals.bought());
    Double twr = Returns.timeWeighted(start, valuations, flows);
    return new Performance(since, days, Returns.xirr(withToday), simpleGain, share(simpleGain, totals.bought()), twr,
        Returns.yearly(twr, days));
  }

  private Allocation allocation(List<Position> positions, Map<String, Book> books, Map<String, Instruments.View> views,
                                Map<String, BigDecimal> wanted, BigDecimal total) {
    List<Slice> slices = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Position p : positions) {
      if (p.inTotal()) {
        seen.add(p.isin());
        slices.add(slice(p.isin(), p.name(), p.value(), wanted.get(p.isin()), total));
      }
    }
    // a target on a position sold out (in euro): all of it to buy
    for (Map.Entry<String, BigDecimal> target : wanted.entrySet()) {
      if (!seen.contains(target.getKey()) && books.containsKey(target.getKey())) {
        slices.add(slice(target.getKey(), views.get(target.getKey()).name(), BigDecimal.ZERO, target.getValue(), total));
      }
    }
    BigDecimal assigned = wanted.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Allocation(slices, assigned, HUNDRED.subtract(assigned));
  }

  private static Slice slice(String isin, String name, BigDecimal value, BigDecimal target, BigDecimal total) {
    BigDecimal actual = total.signum() > 0
        ? value.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    BigDecimal gap = target == null ? null : money(total.multiply(target).divide(HUNDRED, 10, RoundingMode.HALF_UP)
        .subtract(value));
    return new Slice(isin, name, value, actual, target, gap);
  }

  @Transactional(readOnly = true)
  public Targets targets() {
    List<Target> list = targets.findAllByOrderByIsinAsc().stream().map(t -> new Target(t.getIsin(), t.getPercent()))
        .toList();
    BigDecimal assigned = list.stream().map(Target::percent).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new Targets(list, HUNDRED.subtract(assigned));
  }

  /**
   * Replaces every target. Each one: an ISIN of the user, at most once, 0 to 100 percent with at most two decimals
   * (0: no target); together at most 100. Errors: 400 with TARGET_RANGE, TARGET_SUM, UNKNOWN_ISIN, DUPLICATE_ISIN,
   * TOO_MANY_TARGETS.
   */
  @Transactional
  public Targets setTargets(List<Target> wanted) {
    List<Target> list = wanted == null ? List.of() : wanted;
    if (list.size() > MAX_TARGETS) {
      throw bad("TOO_MANY_TARGETS");
    }
    Set<String> own = new HashSet<>(transactions.findDistinctIsin());
    Map<String, BigDecimal> clean = new LinkedHashMap<>();
    BigDecimal sum = BigDecimal.ZERO;
    for (Target t : list) {
      if (t == null || t.isin() == null || !own.contains(t.isin())) {
        throw bad("UNKNOWN_ISIN");
      }
      BigDecimal percent = t.percent();
      if (percent == null || percent.signum() < 0 || percent.compareTo(HUNDRED) > 0
          || percent.stripTrailingZeros().scale() > 2) {
        throw bad("TARGET_RANGE");
      }
      if (clean.put(t.isin(), percent) != null) {
        throw bad("DUPLICATE_ISIN");
      }
      sum = sum.add(percent);
    }
    if (sum.compareTo(HUNDRED) > 0) {
      throw bad("TARGET_SUM");
    }
    targets.deleteAllInBatch();
    clean.forEach((isin, percent) -> {
      if (percent.signum() > 0) {
        targets.save(new AllocationTarget(isin, percent.setScale(2, RoundingMode.HALF_UP)));
      }
    });
    targets.flush();
    return targets();
  }

  private Map<String, BigDecimal> targetMap() {
    Map<String, BigDecimal> map = new LinkedHashMap<>();
    targets.findAllByOrderByIsinAsc().forEach(t -> map.put(t.getIsin(), t.getPercent()));
    return map;
  }

  private static Position withShare(Position p, Double share) {
    return new Position(p.isin(), p.name(), p.currency(), p.inTotal(), p.excluded(), p.quantity(), p.averagePrice(),
        p.invested(), p.lastPrice(), p.lastDate(), p.value(), p.gain(), p.gainShare(), p.tax(), p.netGain(),
        p.realized(), share, p.targetPercent());
  }

  /** the tax on a gain if it were sold today: {@link #TAX_RATE} of it when positive */
  static BigDecimal tax(BigDecimal gain) {
    return gain.signum() > 0 ? money(gain.multiply(TAX_RATE)) : BigDecimal.ZERO.setScale(2);
  }

  private static BigDecimal floor(NavigableMap<LocalDate, BigDecimal> map, LocalDate day) {
    Map.Entry<LocalDate, BigDecimal> entry = map == null ? null : map.floorEntry(day);
    return entry == null ? BigDecimal.ZERO : entry.getValue();
  }

  /** part / whole as a fraction (0.123 = 12.3%), null when the whole is not positive */
  private static Double share(BigDecimal part, BigDecimal whole) {
    return whole == null || whole.signum() <= 0 || part == null ? null
        : part.divide(whole, 8, RoundingMode.HALF_UP).doubleValue();
  }

  private static BigDecimal money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  private static ResponseStatusException bad(String code) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
  }
}
