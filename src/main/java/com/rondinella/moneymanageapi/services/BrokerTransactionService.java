package com.rondinella.moneymanageapi.services;

import com.rondinella.moneymanageapi.common.CsvUtils;
import com.rondinella.moneymanageapi.common.DateUtils;
import com.rondinella.moneymanageapi.dtos.BrokerTransactionDto;
import com.rondinella.moneymanageapi.dtos.GraphPointsDto;
import com.rondinella.moneymanageapi.enitities.BrokerTransaction;
import com.rondinella.moneymanageapi.mappers.BrokerTransactionMapper;
import com.rondinella.moneymanageapi.repositories.BrokerTransactionRepository;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;
import yahoofinance.histquotes.Interval;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

@Service
public class BrokerTransactionService {
  public enum BrokerName {
    Degiro,
    Sanpaolo
  }

  final
  BrokerTransactionRepository brokerTransactionRepository;
  BrokerTransactionMapper brokerTransactionMapper = BrokerTransactionMapper.INSTANCE;

  public BrokerTransactionService(BrokerTransactionRepository brokerTransactionRepository) {
    this.brokerTransactionRepository = brokerTransactionRepository;
  }

  public List<String> findAllIsin() {
    return brokerTransactionRepository.findDistinctIsin();
  }

  @SneakyThrows
  public Object worthGraph(Timestamp fromTimestamp, Timestamp toTimestamp) {
    Calendar from = DateUtils.toCalendar(fromTimestamp);
    Calendar to = DateUtils.toCalendar(toTimestamp);
    Interval interval = Interval.WEEKLY;
    List<String> isinList = findAllIsin();

    LinkedHashSet<String> dates = new LinkedHashSet<>();
    Map<String, Map<String, BigDecimal>> body = new HashMap<>();
    for (String isin : isinList) {
      String stockTicker = luckySearchTicker(isin);
      Stock stock = YahooFinance.get(stockTicker, from, to, interval);
      Map<String, BigDecimal> quotes = new HashMap<>();
      stock.getHistory().forEach((quote) -> {
        String dateString = DateUtils.calendarToString(quote.getDate());
        Timestamp dateTimestamp = DateUtils.stringToTimestamp(dateString);
        BigDecimal totalQuantity = brokerTransactionRepository.totalQuantityByIsinGreaterThan(isin, dateTimestamp);
        BigDecimal value = quote.getClose().multiply(totalQuantity);
        quotes.put(dateString, value);
        dates.add(dateString);
      });
      body.put(isin, quotes);
    }

    LinkedHashSet<String> days = DateUtils.sortDates(dates);
    for (Map<String, BigDecimal> isinData : body.values()) {
      DateUtils.fillGaps(days, isinData);
    }

    //computeTotal
    Map<String, BigDecimal> total = new HashMap<>();
    findAllIsin().forEach(isin -> {
      Map<String, BigDecimal> values = body.get(isin);
      days.forEach(day -> {
        BigDecimal value = values.get(day);
        BigDecimal currentSum = total.getOrDefault(day, BigDecimal.ZERO);
        total.put(day, currentSum.add(value));
      });
    });
    isinList.add("Total");
    body.put("Total", total);

    GraphPointsDto result = new GraphPointsDto();
    result.setLineNames(isinList);
    result.setXLabels(days);
    result.setData(body);

    return result;
  }

  @SneakyThrows
  public BigDecimal worthAtDatetime(Timestamp datetime) {
    List<String> isinList = findAllIsin();
    BigDecimal worth = BigDecimal.ZERO;
    Stock usdEur = luckySearchStock("USD/EUR");
    for (String isin : isinList) {
      String stockTicker = luckySearchTicker(isin);
      Stock stock = YahooFinance.get(stockTicker, DateUtils.toCalendar(datetime));
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
