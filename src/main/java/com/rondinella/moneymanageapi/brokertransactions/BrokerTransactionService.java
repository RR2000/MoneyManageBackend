package com.rondinella.moneymanageapi.brokertransactions;

import com.rondinella.moneymanageapi.banktransactions.BankTransactionService;
import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.common.dtos.GraphPointsDto;
import com.rondinella.moneymanageapi.common.marketdata.MarketData;
import com.rondinella.moneymanageapi.stocks.ParentStock;
import com.rondinella.moneymanageapi.stocks.Stock;
import com.rondinella.moneymanageapi.stocks.StockPrices;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class BrokerTransactionService {
  public enum BrokerName {
    Degiro,
    Sanpaolo
  }

  final
  MarketData marketData;
  final
  BrokerTransactionRepository brokerTransactionRepository;
  final
  BankTransactionService bankTransactionService;
  final
  StockPrices stockPrices;

  public BrokerTransactionService(BrokerTransactionRepository brokerTransactionRepository, BankTransactionService bankTransactionService,
                                  MarketData marketData, StockPrices stockPrices) {
    this.brokerTransactionRepository = brokerTransactionRepository;
    this.stockPrices = stockPrices;
    this.bankTransactionService = bankTransactionService;
    this.marketData = marketData;
  }

  public List<String> findAllIsin() {
    return brokerTransactionRepository.findDistinctIsin();
  }

  /**
   * Monthly value of every ISIN (price times the quantity held that day) and their total, plus the deposits on the
   * Degiro account. Prices come from the database ({@link StockPrices}), the quantities from one read of the
   * transactions of each ISIN: no query per point.
   */
  public GraphPointsDto worthGraph(Timestamp fromTimestamp, Timestamp toTimestamp) {
    Utils.requireSaneRange(fromTimestamp, toTimestamp);
    GraphPointsDto result = new GraphPointsDto();
    List<String> isinList = findAllIsin();
    stockPrices.refresh(isinList);

    for (String isin : isinList) {
      List<BrokerTransaction> transactions = brokerTransactionRepository.findByIsinOrderByDatetime(isin);
      int next = 0;
      BigDecimal totalQuantity = BigDecimal.ZERO;
      boolean bought = false;
      for (Stock quote : stockPrices.between(isin, fromTimestamp, toTimestamp)) {
        String dateString = Utils.convertDateToString(quote.getDatetime());
        Timestamp dateTimestamp = Utils.stringToTimestamp(dateString);
        // quantity held at the start of that day: the transactions until then
        while (next < transactions.size() && !transactions.get(next).getDatetime().after(dateTimestamp)) {
          totalQuantity = totalQuantity.add(transactions.get(next).getQuantity());
          next++;
        }
        if (quote.getClose() != null && (totalQuantity.compareTo(BigDecimal.ZERO) > 0 || bought)) {
          result.addPoint(isin, dateString, quote.getClose().multiply(totalQuantity));
          bought = true;
        }
      }
    }

    result.addPoints("Degiro", bankTransactionService.getDailyDepositSum("degiro", fromTimestamp, toTimestamp));

    result.addTotalColumn("Total Worth", isinList);

    return result;
  }

  @SneakyThrows
  public BigDecimal worthAtDatetime(Timestamp datetime) {
    List<String> isinList = findAllIsin();
    BigDecimal worth = BigDecimal.ZERO;
    ParentStock usdEur = luckySearchStock("USD/EUR");
    for (String isin : isinList) {
      String stockTicker = luckySearchTicker(isin);
      ParentStock parentStock = marketData.get(stockTicker, Utils.toCalendar(datetime));
      BigDecimal thanValue = parentStock.getHistory().get(0).getClose();
      BigDecimal isinQuantity = brokerTransactionRepository.totalQuantityByIsinGreaterThan(isin, datetime);
      isinQuantity = isinQuantity == null ? BigDecimal.ZERO : isinQuantity;
      if (parentStock.getCurrency() == Currency.getInstance("USD"))
        thanValue = thanValue.multiply(usdEur.getClose());
      worth = worth.add(thanValue.multiply(isinQuantity));
    }

    return worth;
  }

  public BigDecimal worthRightNow() {
    List<String> isinList = findAllIsin();
    BigDecimal worth = BigDecimal.ZERO;
    ParentStock usdEur = luckySearchStock("USD/EUR");
    for (String isin : isinList) {
      ParentStock parentStock = luckySearchStock(isin);
      BigDecimal isinQuantity = brokerTransactionRepository.totalQuantityByIsin(isin);
      BigDecimal nowValue = parentStock.getClose();
      if (parentStock.getCurrency() == Currency.getInstance("USD"))
        nowValue = nowValue.multiply(usdEur.getClose());
      worth = worth.add(nowValue.multiply(isinQuantity));
    }

    return worth;
  }

  public String luckySearchTicker(String query) {
    return marketData.luckySearchTicker(query);
  }

  public ParentStock luckySearchStock(String query) {
    try {
      return marketData.get(luckySearchTicker(query));
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Adds the executions of a Degiro export without doubling what is there. With an order id, an execution is the same
   * when order id, moment, ISIN, quantity and price are the same; rows imported before the order id was read (no order
   * id) are found by moment, ISIN, quantity and price and get it. Without an order id, by moment, ISIN, quantity and
   * price. Equal executions are counted: n in the file and m already there update m rows and add n - m.
   */
  private List<BrokerTransaction> addTrades(List<DegiroCsv.Trade> trades) {
    List<BrokerTransaction> toSave = new ArrayList<>();
    Set<Long> claimed = new HashSet<>();
    Map<List<Object>, List<BrokerTransaction>> byMoment = new HashMap<>();
    for (DegiroCsv.Trade trade : trades) {
      List<BrokerTransaction> there = byMoment.computeIfAbsent(List.of(trade.isin(), trade.datetime().toInstant()),
          k -> brokerTransactionRepository.findByIsinAndDatetimeOrderByIdAsc(trade.isin(), trade.datetime()));
      BrokerTransaction row = null;
      if (trade.orderId() != null) {
        row = first(there, claimed, r -> trade.orderId().equals(r.getOrderId()) && same(r, trade));
      }
      if (row == null) {
        row = first(there, claimed, r -> r.getOrderId() == null && same(r, trade));
      }
      if (row == null) {
        row = new BrokerTransaction();
        row.setDatetime(trade.datetime());
        row.setIsin(trade.isin());
        row.setQuantity(trade.quantity());
        row.setStockPrice(trade.price());
      } else {
        claimed.add(row.getId());
      }
      row.setDescription(trade.product());
      row.setFee(trade.fee());
      if (trade.currency() != null) {
        row.setCurrency(trade.currency());
      }
      if (trade.feeCurrency() != null) {
        row.setFeeCurrency(trade.feeCurrency());
      }
      if (trade.orderId() != null) {
        row.setOrderId(trade.orderId());
      }
      toSave.add(row);
    }
    return brokerTransactionRepository.saveAllAndFlush(toSave);
  }

  private static boolean same(BrokerTransaction row, DegiroCsv.Trade trade) {
    return row.getQuantity() != null && row.getQuantity().compareTo(trade.quantity()) == 0
        && row.getStockPrice() != null && row.getStockPrice().compareTo(trade.price()) == 0;
  }

  private static BrokerTransaction first(List<BrokerTransaction> rows, Set<Long> claimed,
                                         java.util.function.Predicate<BrokerTransaction> test) {
    return rows.stream().filter(r -> !claimed.contains(r.getId()) && test.test(r)).findFirst().orElse(null);
  }

  public List<BrokerTransaction> addTransactionsFromCsv(String csvData, BrokerName bankName) {
    if (csvData == null || csvData.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The CSV is empty");
    }
    try {
      return switch (bankName) {
        case Degiro -> addTrades(DegiroCsv.trades(csvData));
        case Sanpaolo -> throw new RuntimeException("Sanpaolo not implemented yet");
      };
    } catch (Exception e) {
      // unreadable file or not a Degiro export: the user's fault, not a server error (and no detail of the rows)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot read the " + bankName + " CSV", e);
    }
  }

}
