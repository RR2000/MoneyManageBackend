package com.rondinella.moneymanageapi.demo;

import com.rondinella.moneymanageapi.banktransactions.BankTransactionDto;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionService;
import com.rondinella.moneymanageapi.brokertransactions.BrokerTransaction;
import com.rondinella.moneymanageapi.brokertransactions.BrokerTransactionRepository;
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
 * accounts, a fake ETF bought every month (with its own fake price history, no market data calls) and a flat.
 * Every name says "esempio": nothing here is real.
 */
@Service
public class SampleData {

  public static final String CHECKING = "Conto_corrente";
  public static final String SAVINGS = "Risparmi";
  public static final String ETF_ISIN = "XX0000000001";
  private static final String ETF_SYMBOL = "DEMO-ETF";
  private static final String[] SMALL_EXPENSES = {"Ristorante", "Benzina", "Farmacia", "Libri", "Cinema", "Bar"};

  private final BankTransactionService bankTransactionService;
  private final BrokerTransactionRepository brokerTransactionRepository;
  private final StockRepository stockRepository;
  private final RealEstateService realEstateService;

  public SampleData(BankTransactionService bankTransactionService, BrokerTransactionRepository brokerTransactionRepository,
                    StockRepository stockRepository, RealEstateService realEstateService) {
    this.bankTransactionService = bankTransactionService;
    this.brokerTransactionRepository = brokerTransactionRepository;
    this.stockRepository = stockRepository;
    this.realEstateService = realEstateService;
  }

  /** Fills the schema of the current user (see TenantContext.runAs). */
  public void load() {
    LocalDate today = LocalDate.now();
    Random rng = new Random(2024);
    bankMovements(today, rng);
    etf(today, rng);
    realEstate();
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
    bankTransactionService.addTransactions(txs);
    bankTransactionService.computeCumulativeAmount(CHECKING, new BigDecimal("3480.25"));
    bankTransactionService.computeCumulativeAmount(SAVINGS, new BigDecimal("9120.00"));
  }

  private void etf(LocalDate today, Random rng) {
    List<Stock> prices = new ArrayList<>();
    List<BrokerTransaction> buys = new ArrayList<>();
    double price = 80;
    for (LocalDate month = today.minusYears(3).withDayOfMonth(1); !month.isAfter(today); month = month.plusMonths(1)) {
      price = price * Math.exp(0.006 + 0.04 * rng.nextGaussian());
      BigDecimal close = money(price);
      Timestamp at = Timestamp.valueOf(month.atStartOfDay());
      prices.add(new Stock(ETF_SYMBOL, ETF_ISIN, at, close, close, close, close, Currency.getInstance("EUR"), 0L));

      BrokerTransaction buy = new BrokerTransaction();
      buy.setDatetime(Timestamp.valueOf(month.withDayOfMonth(Math.min(3, month.lengthOfMonth())).atTime(10, 0)));
      buy.setDescription("ETF Mondo (esempio)");
      buy.setIsin(ETF_ISIN);
      buy.setQuantity(BigDecimal.valueOf(2 + rng.nextInt(2)));
      buy.setStockPrice(close);
      buy.setFee(BigDecimal.ONE.negate());
      if (!buy.getDatetime().toLocalDateTime().toLocalDate().isAfter(today)) {
        buys.add(buy);
      }
    }
    stockRepository.saveAllAndFlush(prices);
    brokerTransactionRepository.saveAllAndFlush(buys);
  }

  private void realEstate() {
    RealEstateDto flat = new RealEstateDto(null, "Bilocale (esempio)", "Via dell'Esempio 1, Milano",
        LocalDate.of(2019, 6, 15), new BigDecimal("145000"), new BigDecimal("168000"),
        new BigDecimal("650"), new BigDecimal("120"), "EUR");
    realEstateService.save(flat);
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
