package com.rondinella.moneymanageapi.services;

import com.rondinella.moneymanageapi.common.CsvUtils;
import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.dtos.BrokerTransactionDto;
import com.rondinella.moneymanageapi.dtos.GraphPointsDto;
import com.rondinella.moneymanageapi.enitities.BrokerTransaction;
import com.rondinella.moneymanageapi.mappers.BrokerTransactionMapper;
import com.rondinella.moneymanageapi.repositories.BrokerTransactionRepository;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes.HistoricalQuote;
import yahoofinance.histquotes.Interval;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

@Service
public class BrokerTransactionService {
  public enum BrokerName {
    Degiro,
    Sanpaolo
  }

  final
  BrokerTransactionRepository brokerTransactionRepository;
  final
  BankTransactionService bankTransactionService;
  BrokerTransactionMapper brokerTransactionMapper = BrokerTransactionMapper.INSTANCE;

  public BrokerTransactionService(BrokerTransactionRepository brokerTransactionRepository, BankTransactionService bankTransactionService) {
    this.brokerTransactionRepository = brokerTransactionRepository;
    this.bankTransactionService = bankTransactionService;
  }

  public List<String> findAllIsin() {
    return brokerTransactionRepository.findDistinctIsin();
  }

  @SneakyThrows
  public GraphPointsDto worthGraph(Timestamp fromTimestamp, Timestamp toTimestamp) {
    GraphPointsDto result = new GraphPointsDto();

    Calendar from = Utils.toCalendar(fromTimestamp);
    Calendar to = Utils.toCalendar(toTimestamp);
    Interval interval = Interval.MONTHLY;
    List<String> isinList = findAllIsin();

    for (String isin : isinList) {
      String stockTicker = luckySearchTicker(isin);
      Stock stock = YahooFinance.get(stockTicker, from, to, interval);
      boolean bought = false;
      for (HistoricalQuote quote : stock.getHistory()) {
        String dateString = Utils.calendarToString(quote.getDate());
        Timestamp dateTimestamp = Utils.stringToTimestamp(dateString);
        BigDecimal totalQuantity = brokerTransactionRepository.totalQuantityByIsinGreaterThan(isin, dateTimestamp);
        if (quote.getClose() != null && (totalQuantity.compareTo(BigDecimal.ZERO) > 0 || bought)) {
          BigDecimal value = quote.getClose().multiply(totalQuantity);
          result.addPoint(isin, dateString, value);
          bought = true;
        }
      }
    }

    result.addPoints("Degiro", bankTransactionService.getDailyDepositSum("degiro", fromTimestamp, toTimestamp));

    result.validateAndFillMissingValues();
    result.addTotalColumn("Total Worth", isinList);

    return result;
  }

  @SneakyThrows
  public BigDecimal worthAtDatetime(Timestamp datetime) {
    List<String> isinList = findAllIsin();
    BigDecimal worth = BigDecimal.ZERO;
    Stock usdEur = luckySearchStock("USD/EUR");
    for (String isin : isinList) {
      String stockTicker = luckySearchTicker(isin);
      Stock stock = YahooFinance.get(stockTicker, Utils.toCalendar(datetime));
      BigDecimal thanValue = stock.getHistory().get(0).getClose();
      BigDecimal isinQuantity = brokerTransactionRepository.totalQuantityByIsinGreaterThan(isin, datetime);
      isinQuantity = isinQuantity == null ? BigDecimal.ZERO : isinQuantity;
      if (stock.getCurrency().equals("USD"))
        thanValue = thanValue.multiply(usdEur.getQuote().getPreviousClose());
      worth = worth.add(thanValue.multiply(isinQuantity));
    }

    return worth;
  }

  public BigDecimal worthRightNow() {
    List<String> isinList = findAllIsin();
    BigDecimal worth = BigDecimal.ZERO;
    Stock usdEur = luckySearchStock("USD/EUR");
    for (String isin : isinList) {
      Stock stock = luckySearchStock(isin);
      BigDecimal isinQuantity = brokerTransactionRepository.totalQuantityByIsin(isin);
      BigDecimal nowValue = stock.getQuote().getPreviousClose();
      if (stock.getCurrency().equals("USD"))
        nowValue = nowValue.multiply(usdEur.getQuote().getPreviousClose());
      worth = worth.add(nowValue.multiply(isinQuantity));
    }

    return worth;
  }

  public String luckySearchTicker(String query) {
    return YahooFinance.luckySearchTicker(query);
  }

  public Stock luckySearchStock(String query) {
    try {
      return YahooFinance.get(luckySearchTicker(query));
    } catch (IOException e) {
      return null;
    }
  }

  private List<BrokerTransaction> addTransactions(List<BrokerTransactionDto> brokerTransactionDtos) {
    return brokerTransactionRepository.saveAllAndFlush(brokerTransactionMapper.toEntity(brokerTransactionDtos));
  }

  private List<BrokerTransactionDto> degiroCsv(String csvData) throws IOException {
    List<Map<String, String>> degiroMap = CsvUtils.csvToMap(csvData);

    return brokerTransactionMapper.toDtoFromDegiro(degiroMap);
  }

  public List<BrokerTransaction> addTransactionsFromCsv(String csvData, BrokerName bankName) {
    if (csvData == null || csvData.isEmpty()) {
      throw new RuntimeException("CSV data is empty");
    }
    try {
      List<BrokerTransactionDto> brokerTransactionDtos;
      switch (bankName) {
        case Degiro -> brokerTransactionDtos = degiroCsv(csvData);
        case Sanpaolo -> throw new RuntimeException("Sanpaolo not implemented yet");
        default -> throw new RuntimeException("Impossible to be here");
      }

      return addTransactions(brokerTransactionDtos);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read CSV data", e);
    } catch (Exception e) {
      // Handle any other exceptions that might occur during processing
      throw new RuntimeException("Error processing CSV data", e);
    }
  }

}
