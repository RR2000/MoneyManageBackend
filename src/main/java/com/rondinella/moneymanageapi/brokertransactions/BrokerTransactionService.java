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
import java.util.Currency;
import java.util.List;
import java.util.Map;

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
  BrokerTransactionMapper brokerTransactionMapper = BrokerTransactionMapper.INSTANCE;

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

  private List<BrokerTransaction> addTransactions(List<BrokerTransactionDto> brokerTransactionDtos) {
    return brokerTransactionRepository.saveAllAndFlush(brokerTransactionMapper.toEntity(brokerTransactionDtos));
  }

  private List<BrokerTransactionDto> degiroCsv(String csvData) throws IOException {
    List<Map<String, String>> degiroMap = Utils.csvToMap(csvData);

    return brokerTransactionMapper.toDtoFromDegiro(degiroMap);
  }

  public List<BrokerTransaction> addTransactionsFromCsv(String csvData, BrokerName bankName) {
    if (csvData == null || csvData.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The CSV is empty");
    }
    try {
      List<BrokerTransactionDto> brokerTransactionDtos;
      switch (bankName) {
        case Degiro -> brokerTransactionDtos = degiroCsv(csvData);
        case Sanpaolo -> throw new RuntimeException("Sanpaolo not implemented yet");
        default -> throw new RuntimeException("Impossible to be here");
      }

      return addTransactions(brokerTransactionDtos);
    } catch (Exception e) {
      // unreadable file or not a Degiro export: the user's fault, not a server error (and no detail of the rows)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot read the " + bankName + " CSV", e);
    }
  }

}
