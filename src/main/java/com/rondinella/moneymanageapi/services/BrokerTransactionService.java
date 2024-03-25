package com.rondinella.moneymanageapi.services;

import com.rondinella.moneymanageapi.common.CsvUtils;
import com.rondinella.moneymanageapi.dtos.BrokerTransactionDto;
import com.rondinella.moneymanageapi.enitities.BrokerTransaction;
import com.rondinella.moneymanageapi.mappers.BrokerTransactionMapper;
import com.rondinella.moneymanageapi.repositories.BrokerTransactionRepository;
import org.springframework.stereotype.Service;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;

import java.io.IOException;
import java.math.BigDecimal;
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
  BrokerTransactionMapper brokerTransactionMapper = BrokerTransactionMapper.INSTANCE;

  public BrokerTransactionService(BrokerTransactionRepository brokerTransactionRepository) {
    this.brokerTransactionRepository = brokerTransactionRepository;
  }

  public List<String> findAllIsin() {
    return brokerTransactionRepository.findDistinctIsin();
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
