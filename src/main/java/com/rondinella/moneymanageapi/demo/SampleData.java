package com.rondinella.moneymanageapi.demo;

import com.rondinella.moneymanageapi.assets.Assets;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionDto;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionService;
import com.rondinella.moneymanageapi.brokertransactions.BrokerTransaction;
import com.rondinella.moneymanageapi.brokertransactions.BrokerTransactionRepository;
import com.rondinella.moneymanageapi.categories.Categories;
import com.rondinella.moneymanageapi.realestate.RealEstateDto;
import com.rondinella.moneymanageapi.realestate.RealEstateService;
import com.rondinella.moneymanageapi.stocks.Stock;
import com.rondinella.moneymanageapi.stocks.StockRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Random;

/**
 * Made-up data for a new account, so the dashboard has something to show: a year of movements on two bank
 * accounts, a fake ETF bought every month (a few sold four months ago) and a fake bond ETF bought every three months
 * (with their own fake price history, no market data calls), a flat, a car, some gold and a car loan; categories
 * with their rules (rent, bills, subscriptions and the car insurance are fixed costs; some small expenses stay without
 * a category). Every name says "esempio": nothing here is real.
 */
@Service
public class SampleData {

  public static final String CHECKING = "Conto_corrente";
  public static final String SAVINGS = "Risparmi";
  public static final String ETF_ISIN = "XX0000000001";
  public static final String BOND_ISIN = "XX0000000002";
  private static final String ETF_SYMBOL = "DEMO-ETF";
  private static final String BOND_SYMBOL = "DEMO-BOND";
  private static final String[] SMALL_EXPENSES = {"Ristorante", "Benzina", "Farmacia", "Libri", "Cinema", "Bar"};

  private final BankTransactionService bankTransactionService;
  private final BrokerTransactionRepository brokerTransactionRepository;
  private final StockRepository stockRepository;
  private final RealEstateService realEstateService;
  private final Assets assets;
  private final Categories categories;

  public SampleData(BankTransactionService bankTransactionService, BrokerTransactionRepository brokerTransactionRepository,
                    StockRepository stockRepository, RealEstateService realEstateService, Assets assets,
                    Categories categories) {
    this.bankTransactionService = bankTransactionService;
    this.brokerTransactionRepository = brokerTransactionRepository;
    this.stockRepository = stockRepository;
    this.realEstateService = realEstateService;
    this.assets = assets;
    this.categories = categories;
  }

  /** Fills the schema of the current user (see TenantContext.runAs). */
  public void load() {
    LocalDate today = LocalDate.now();
    Random rng = new Random(2024);
    // before the movements: the rules give them their category as they arrive
    categories();
    bankMovements(today, rng);
    // own seed: the price path does not depend on how many bank movements came before (a calm, rising one)
    etf(today, new Random(3));
    bonds(today, new Random(5));
    realEstate();
    otherAssetsAndDebts(today);
  }

  private void bankMovements(LocalDate today, Random rng) {
    List<BankTransactionDto> txs = new ArrayList<>();
    LocalDate start = today.minusMonths(12).withDayOfMonth(1);
    for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
      int dom = day.getDayOfMonth();
      if (dom == 1) txs.add(tx(CHECKING, day, 9, "Affitto (esempio)", -720));
      if (dom == 5) txs.add(tx(CHECKING, day, 10, "Bollette luce e gas", -(70 + rng.nextInt(60))));
      if (dom == 27) txs.add(tx(CHECKING, day, 8, "Stipendio (esempio)", 2150));
      if (dom == 28) {
        txs.add(tx(CHECKING, day, 12, "Giroconto verso i risparmi", -300));
        txs.add(tx(SAVINGS, day, 12, "Giroconto dal conto corrente", 300));
      }
      if (dom == 15 && day.getMonthValue() % 3 == 0) txs.add(tx(SAVINGS, day, 7, "Interessi", 18 + rng.nextInt(10)));
      if (day.getDayOfWeek() == DayOfWeek.SATURDAY) txs.add(tx(CHECKING, day, 11, "Spesa supermercato", -(45 + rng.nextInt(65))));
      if (rng.nextInt(5) == 0) {
        String what = SMALL_EXPENSES[rng.nextInt(SMALL_EXPENSES.length)];
        txs.add(tx(CHECKING, day, 19, what, -(8 + rng.nextInt(55))));
      }
    }
    fixedCosts(txs, start, today);
    bankTransactionService.addTransactions(txs);
    bankTransactionService.computeCumulativeAmount(CHECKING, new BigDecimal("3480.25"));
    bankTransactionService.computeCumulativeAmount(SAVINGS, new BigDecimal("9120.00"));
  }

  /** Fixed costs (Casa, Bollette, Abbonamenti, Assicurazioni) and three other categories, each with its rules. */
  private void categories() {
    categories.seed("Casa (esempio)", true, "affitto");
    categories.seed("Bollette (esempio)", true, "bollette luce", "bolletta gas");
    categories.seed("Abbonamenti (esempio)", true, "abbonamento");
    categories.seed("Assicurazioni (esempio)", true, "assicurazione");
    categories.seed("Spesa (esempio)", false, "supermercato");
    categories.seed("Stipendio (esempio)", false, "stipendio");
    categories.seed("Giroconti (esempio)", false, "giroconto");
  }

  /**
   * Costs with other cadences, with a random of their own (the movements before stay the same): a phone every month,
   * gas every two months, the car insurance once a year (on the first day of the first month and of this month).
   */
  private static void fixedCosts(List<BankTransactionDto> txs, LocalDate start, LocalDate today) {
    Random rng = new Random(11);
    for (LocalDate month = start; !month.isAfter(today); month = month.plusMonths(1)) {
      LocalDate phone = month.withDayOfMonth(3);
      if (!phone.isAfter(today)) {
        txs.add(tx(CHECKING, phone, 7, "Abbonamento telefono (esempio)", -12.99));
      }
      LocalDate gas = month.withDayOfMonth(12);
      if (month.getMonthValue() % 2 == start.getMonthValue() % 2 && !gas.isAfter(today)) {
        txs.add(tx(CHECKING, gas, 10, "Bolletta gas (esempio)", -(60 + rng.nextInt(35))));
      }
    }
    txs.add(tx(CHECKING, start, 15, "Assicurazione auto (esempio)", -540));
    txs.add(tx(CHECKING, today.withDayOfMonth(1), 15, "Assicurazione auto (esempio)", -540));
  }

  private void etf(LocalDate today, Random rng) {
    List<Stock> prices = new ArrayList<>();
    List<BrokerTransaction> trades = new ArrayList<>();
    double price = 80;
    LocalDate sale = today.minusMonths(4).withDayOfMonth(1);
    for (LocalDate month = today.minusYears(3).withDayOfMonth(1); !month.isAfter(today); month = month.plusMonths(1)) {
      price = price * Math.exp(0.006 + 0.04 * rng.nextGaussian());
      BigDecimal close = money(price);
      prices.add(price(ETF_SYMBOL, ETF_ISIN, month, close));
      trades.add(trade(ETF_ISIN, "ETF Mondo (esempio)", month, BigDecimal.valueOf(2 + rng.nextInt(2)), close));
      if (month.equals(sale)) {
        trades.add(trade(ETF_ISIN, "ETF Mondo (esempio)", month.plusDays(10), BigDecimal.valueOf(-6), close));
      }
    }
    save(prices, trades, today);
  }

  private void bonds(LocalDate today, Random rng) {
    List<Stock> prices = new ArrayList<>();
    List<BrokerTransaction> trades = new ArrayList<>();
    double price = 50;
    LocalDate start = today.minusYears(2).withDayOfMonth(1);
    for (LocalDate month = start; !month.isAfter(today); month = month.plusMonths(1)) {
      price = price * Math.exp(0.002 + 0.01 * rng.nextGaussian());
      BigDecimal close = money(price);
      prices.add(price(BOND_SYMBOL, BOND_ISIN, month, close));
      if (month.getMonthValue() % 3 == start.getMonthValue() % 3) {
        trades.add(trade(BOND_ISIN, "Obbligazioni Euro (esempio)", month, BigDecimal.TEN, close));
      }
    }
    save(prices, trades, today);
  }

  private static Stock price(String symbol, String isin, LocalDate month, BigDecimal close) {
    return new Stock(symbol, isin, Timestamp.valueOf(month.atStartOfDay()), close, close, close, close,
        Currency.getInstance("EUR"), 0L);
  }

  /** a trade on the 3rd of the month (or on that day, after the 1st), fee 1 euro */
  private static BrokerTransaction trade(String isin, String name, LocalDate day, BigDecimal quantity, BigDecimal price) {
    BrokerTransaction t = new BrokerTransaction();
    LocalDate on = day.getDayOfMonth() == 1 ? day.withDayOfMonth(Math.min(3, day.lengthOfMonth())) : day;
    t.setDatetime(Timestamp.valueOf(on.atTime(10, 0)));
    t.setDescription(name);
    t.setIsin(isin);
    t.setQuantity(quantity);
    t.setStockPrice(price);
    t.setFee(BigDecimal.ONE.negate());
    t.setCurrency("EUR");
    t.setFeeCurrency("EUR");
    return t;
  }

  private void save(List<Stock> prices, List<BrokerTransaction> trades, LocalDate today) {
    stockRepository.saveAllAndFlush(prices);
    brokerTransactionRepository.saveAllAndFlush(trades.stream()
        .filter(t -> !t.getDatetime().toLocalDateTime().toLocalDate().isAfter(today)).toList());
  }

  private void realEstate() {
    RealEstateDto flat = new RealEstateDto(null, "Bilocale (esempio)", "Via dell'Esempio 1, Milano",
        LocalDate.of(2019, 6, 15), new BigDecimal("145000"), new BigDecimal("168000"),
        new BigDecimal("650"), new BigDecimal("120"), "EUR");
    realEstateService.save(flat);
  }

  /** a car valued three months ago, gold without a date (counted on every day), a car loan with payment and rate */
  private void otherAssetsAndDebts(LocalDate today) {
    assets.create(new Assets.OtherAssetRequest("Auto (esempio)", "VEHICLE", new BigDecimal("9500"), "EUR",
        today.minusMonths(3).withDayOfMonth(1)), today);
    assets.create(new Assets.OtherAssetRequest("Oro (esempio)", "GOLD", new BigDecimal("2400"), "EUR", null), today);
    assets.create(new Assets.DebtRequest("Prestito auto (esempio)", new BigDecimal("6200"), "EUR", new BigDecimal("5.9"),
        new BigDecimal("280"), null), today);
  }

  private static BankTransactionDto tx(String account, LocalDate day, int hour, String description, double amount) {
    BankTransactionDto dto = new BankTransactionDto();
    dto.setAccount(account);
    dto.setDatetime(Timestamp.valueOf(LocalDateTime.of(day, java.time.LocalTime.of(hour, 0))));
    dto.setDescription(description);
    dto.setAmount(money(amount));
    dto.setFee(BigDecimal.ZERO);
    dto.setCurrency("EUR");
    return dto;
  }

  private static BigDecimal money(double value) {
    return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
  }
}
