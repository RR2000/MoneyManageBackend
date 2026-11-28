package com.rondinella.moneymanageapi.networth;

import com.rondinella.moneymanageapi.assets.Assets;
import com.rondinella.moneymanageapi.assets.Debt;
import com.rondinella.moneymanageapi.assets.DebtRepository;
import com.rondinella.moneymanageapi.assets.OtherAsset;
import com.rondinella.moneymanageapi.assets.OtherAssetRepository;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionRepository;
import com.rondinella.moneymanageapi.banktransactions.DailyBalance;
import com.rondinella.moneymanageapi.banktransactions.DailyBalanceRepository;
import com.rondinella.moneymanageapi.brokertransactions.BrokerTransactionRepository;
import com.rondinella.moneymanageapi.brokertransactions.BrokerTransactionService;
import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.common.dtos.GraphPointsDto;
import com.rondinella.moneymanageapi.realestate.RealEstate;
import com.rondinella.moneymanageapi.realestate.RealEstateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The net worth of the user over time: cash (the balances of the accounts), investments (the worth of the portfolio),
 * real estate, other assets and debts, at the end of every month and today. Only euro: accounts, real estate, other
 * assets and debts in another currency are left out of the total and counted apart (nothing is converted).
 * Real estate counts from its purchase date on, another asset from the day of its estimate on (on every day without
 * one), a debt with what is left today on every day: its past is not rebuilt (see {@link Assets}).
 */
@Service
public class NetWorth {

  public static final String CURRENCY = "EUR";
  /** the column of {@link BrokerTransactionService#worthGraph} with the worth of the whole portfolio */
  static final String TOTAL_WORTH = "Total Worth";
  /** days of the change shown next to today's net worth */
  static final int CHANGE_DAYS = 30;

  /** The parts of the net worth on one day; debts are negative. */
  public record Point(LocalDate date, BigDecimal cash, BigDecimal investments, BigDecimal realEstate,
                      BigDecimal otherAssets, BigDecimal debts, BigDecimal total) {
  }

  /** What is not in the total because it is not in euro: how many of each, and their currencies. */
  public record Excluded(int accounts, int realEstates, int otherAssets, int debts, List<String> currencies) {
  }

  /**
   * {@code points}: from {@code from}, the end of every month, and today (oldest first); {@code today} and
   * {@code before} (30 days ago) for the change; {@code accounts}, {@code realEstates}, {@code otherAssets},
   * {@code debts}: how many are in the total.
   */
  public record Answer(String currency, LocalDate from, Point today, Point before, List<Point> points, int accounts,
                       int realEstates, int otherAssets, int debts, int instruments, Excluded excluded) {
  }

  /** what is in the total besides accounts and investments */
  private record Owned(List<RealEstate> estates, List<OtherAsset> others, List<Debt> debts) {
  }

  private final DailyBalanceRepository dailyBalances;
  private final BankTransactionRepository transactions;
  private final BrokerTransactionRepository brokerTransactions;
  private final BrokerTransactionService broker;
  private final RealEstateRepository realEstates;
  private final OtherAssetRepository otherAssets;
  private final DebtRepository debts;

  public NetWorth(DailyBalanceRepository dailyBalances, BankTransactionRepository transactions,
                  BrokerTransactionRepository brokerTransactions, BrokerTransactionService broker,
                  RealEstateRepository realEstates, OtherAssetRepository otherAssets, DebtRepository debts) {
    this.dailyBalances = dailyBalances;
    this.transactions = transactions;
    this.brokerTransactions = brokerTransactions;
    this.broker = broker;
    this.realEstates = realEstates;
    this.otherAssets = otherAssets;
    this.debts = debts;
  }

  /** From {@code from} (null: the first day with data) to {@code today}. */
  public Answer compute(LocalDate from, LocalDate today) {
    // the balances of every account by day, and the currency of each (the most used)
    Map<String, NavigableMap<LocalDate, BigDecimal>> balances = new HashMap<>();
    for (DailyBalance b : dailyBalances.findAllByOrderByAccountAscDateAsc()) {
      if (b.getBalance() != null) {
        balances.computeIfAbsent(b.getAccount(), k -> new TreeMap<>()).put(b.getDate(), b.getBalance());
      }
    }
    Map<String, String> currencies = new HashMap<>();
    for (Object[] row : transactions.countByAccountAndCurrency()) {
      currencies.putIfAbsent((String) row[0], (String) row[1]);
    }
    Set<String> otherCurrencies = new TreeSet<>();
    List<NavigableMap<LocalDate, BigDecimal>> cash = new ArrayList<>();
    int accounts = 0;
    int excludedAccounts = 0;
    for (String account : Stream.concat(transactions.findDistinctAccounts().stream(), balances.keySet().stream())
        .distinct().toList()) {
      String currency = currencies.getOrDefault(account, CURRENCY);
      if (CURRENCY.equalsIgnoreCase(currency)) {
        accounts++;
        cash.add(balances.getOrDefault(account, new TreeMap<>()));
      } else {
        excludedAccounts++;
        otherCurrencies.add(currency.toUpperCase());
      }
    }

    List<RealEstate> estates = new ArrayList<>();
    int excludedEstates = 0;
    for (RealEstate estate : realEstates.findAll()) {
      String currency = Assets.currencyOf(estate.getCurrency());
      if (CURRENCY.equals(currency)) {
        estates.add(estate);
      } else {
        excludedEstates++;
        otherCurrencies.add(currency);
      }
    }
    List<OtherAsset> others = new ArrayList<>();
    int excludedOthers = 0;
    for (OtherAsset asset : otherAssets.findAll()) {
      String currency = Assets.currencyOf(asset.getCurrency());
      if (CURRENCY.equals(currency)) {
        others.add(asset);
      } else {
        excludedOthers++;
        otherCurrencies.add(currency);
      }
    }
    List<Debt> owed = new ArrayList<>();
    int excludedDebts = 0;
    for (Debt debt : debts.findAll()) {
      String currency = Assets.currencyOf(debt.getCurrency());
      if (CURRENCY.equals(currency)) {
        owed.add(debt);
      } else {
        excludedDebts++;
        otherCurrencies.add(currency);
      }
    }
    Owned owned = new Owned(estates, others, owed);

    LocalDate start = from != null ? from : firstDay(owned, today);
    if (start.isAfter(today)) {
      start = today;
    }
    if (start.isBefore(today.minusYears(Utils.MAX_YEARS))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FROM_TOO_OLD");
    }
    LocalDate before = today.minusDays(CHANGE_DAYS);
    int instruments = brokerTransactions.findDistinctIsin().size();
    NavigableMap<LocalDate, BigDecimal> worth = instruments == 0 ? new TreeMap<>()
        : investments(start.isBefore(before) ? start : before, today);

    List<Point> points = new ArrayList<>();
    for (LocalDate day : days(start, today)) {
      points.add(point(day, cash, worth, owned));
    }
    return new Answer(CURRENCY, start, points.getLast(), point(before, cash, worth, owned), points, accounts,
        estates.size(), others.size(), owed.size(), instruments,
        new Excluded(excludedAccounts, excludedEstates, excludedOthers, excludedDebts, List.copyOf(otherCurrencies)));
  }

  /** {@code from}, the last day of every month after it and before today, and today */
  public static List<LocalDate> days(LocalDate from, LocalDate today) {
    List<LocalDate> days = new ArrayList<>();
    days.add(from);
    for (LocalDate end = YearMonth.from(from).atEndOfMonth(); end.isBefore(today);
         end = YearMonth.from(end).plusMonths(1).atEndOfMonth()) {
      if (end.isAfter(from)) {
        days.add(end);
      }
    }
    if (today.isAfter(from)) {
      days.add(today);
    }
    return days;
  }

  private static Point point(LocalDate day, List<NavigableMap<LocalDate, BigDecimal>> cash,
                             NavigableMap<LocalDate, BigDecimal> worth, Owned owned) {
    BigDecimal money = BigDecimal.ZERO;
    for (NavigableMap<LocalDate, BigDecimal> account : cash) {
      Map.Entry<LocalDate, BigDecimal> last = account.floorEntry(day);
      if (last != null) {
        money = money.add(last.getValue());
      }
    }
    Map.Entry<LocalDate, BigDecimal> invested = worth.floorEntry(day);
    BigDecimal investments = invested == null ? BigDecimal.ZERO : invested.getValue();
    BigDecimal property = BigDecimal.ZERO;
    for (RealEstate estate : owned.estates()) {
      if (Assets.counts(estate, day)) {
        property = property.add(estate.getCurrentEstimatedValue());
      }
    }
    BigDecimal things = BigDecimal.ZERO;
    for (OtherAsset asset : owned.others()) {
      if (Assets.counts(asset, day)) {
        things = things.add(asset.getEstimatedValue());
      }
    }
    // what is left today, also on the days before: the past of a debt is not known
    BigDecimal debts = BigDecimal.ZERO;
    for (Debt debt : owned.debts()) {
      debts = debts.subtract(debt.getBalance());
    }
    return new Point(day, money, investments, property, things, debts,
        money.add(investments).add(property).add(things).add(debts));
  }

  /**
   * The worth of the portfolio by price date, from a while before {@code from} (prices can be weekly or monthly: the
   * last one before a day is its value).
   */
  private NavigableMap<LocalDate, BigDecimal> investments(LocalDate from, LocalDate today) {
    LocalDate start = from.minusMonths(3);
    if (start.isBefore(today.minusYears(Utils.MAX_YEARS))) {
      start = today.minusYears(Utils.MAX_YEARS);
    }
    GraphPointsDto graph = broker.worthGraph(Timestamp.valueOf(start.atStartOfDay()),
        Timestamp.valueOf(today.plusDays(1).atStartOfDay()));
    NavigableMap<LocalDate, BigDecimal> worth = new TreeMap<>();
    Map<String, BigDecimal> total = graph.getGraph(TOTAL_WORTH);
    if (total != null) {
      total.forEach((day, value) -> {
        if (value != null) {
          worth.put(LocalDate.parse(day), value);
        }
      });
    }
    return worth;
  }

  /** the first day with a balance, an investment, a property or an estimate of another asset; today when there is nothing */
  private LocalDate firstDay(Owned owned, LocalDate today) {
    LocalDate first = today;
    LocalDate balance = dailyBalances.findFirstDate();
    if (balance != null && balance.isBefore(first)) {
      first = balance;
    }
    Timestamp bought = brokerTransactions.findFirstDatetime();
    if (bought != null && bought.toLocalDateTime().toLocalDate().isBefore(first)) {
      first = bought.toLocalDateTime().toLocalDate();
    }
    for (RealEstate estate : owned.estates()) {
      if (estate.getPurchaseDate() != null && estate.getPurchaseDate().isBefore(first)) {
        first = estate.getPurchaseDate();
      }
    }
    for (OtherAsset asset : owned.others()) {
      if (asset.getValuedOn() != null && asset.getValuedOn().isBefore(first)) {
        first = asset.getValuedOn();
      }
    }
    return first.isBefore(today.minusYears(Utils.MAX_YEARS)) ? today.minusYears(Utils.MAX_YEARS) : first;
  }
}
