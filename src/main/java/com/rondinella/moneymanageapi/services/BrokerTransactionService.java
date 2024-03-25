package com.rondinella.moneymanageapi.services;

import com.rondinella.moneymanageapi.common.CsvUtils;
import com.rondinella.moneymanageapi.dtos.BrokerTransactionDto;
import com.rondinella.moneymanageapi.enitities.BrokerTransaction;
import com.rondinella.moneymanageapi.mappers.BrokerTransactionMapper;
import com.rondinella.moneymanageapi.repositories.BrokerTransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;

import java.io.IOException;
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
