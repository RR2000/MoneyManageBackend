package com.rondinella.moneymanageapi.services;

import com.rondinella.moneymanageapi.common.DateUtils;
import com.rondinella.moneymanageapi.dtos.BankTransactionDto;
import com.rondinella.moneymanageapi.dtos.GraphPointsDto;
import com.rondinella.moneymanageapi.enitities.BankTransaction;
import com.rondinella.moneymanageapi.mappers.BankTransactionMapper;
import com.rondinella.moneymanageapi.repositories.BankTransactionRepository;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BankTransactionService {
  public enum BankName {
    Revolut,
    Sanpaolo
  }

  final
  BankTransactionRepository bankTransactionRepository;
  BankTransactionMapper bankTransactionMapper = BankTransactionMapper.INSTANCE;

  public BankTransactionService(BankTransactionRepository bankTransactionRepository) {
    this.bankTransactionRepository = bankTransactionRepository;
  }

  public List<BankTransactionDto> findAllTransactions() {
    return bankTransactionMapper.toDto(bankTransactionRepository.findAll());
  }

  public List<BankTransactionDto> findTransactionsByAccount(String accountName) {
    return bankTransactionMapper.toDto(bankTransactionRepository.findTransactionByAccountOrderByDatetime(accountName));
  }

  public List<String> findAllAccounts() {
    return bankTransactionRepository.findDistinctAccounts();
  }

  public boolean computeCumulativeAmount(String account, BigDecimal todayMoney) {
    List<BankTransaction> bankTransactions = bankTransactionRepository.findTransactionByAccountOrderByDatetimeDesc(account);

    BigDecimal cumulative = todayMoney; // Initialize with today's money
    bankTransactions.get(0).setCumulativeAmount(cumulative);
    for (int i = 1; i < bankTransactions.size(); i++) {
      BankTransaction previous = bankTransactions.get(i - 1);
      BankTransaction bankTransaction = bankTransactions.get(i);
      cumulative = cumulative.subtract(previous.getAmount()); // Add current transaction's amount
      bankTransaction.setCumulativeAmount(cumulative); // Set cumulative amount for the transaction
    }

    bankTransactionRepository.saveAllAndFlush(bankTransactions);

    return true;
  }

  public BigDecimal amountOnThatDay(String accountName, Timestamp thatDay) {
    Map<String, BigDecimal> base = new HashMap<>();
    base.put("Revolut_Current", new BigDecimal("0"));
    base.put("Revolut_Pocket", new BigDecimal("0"));
    base.put("Revolut_Savings", new BigDecimal("0"));

    List<BankTransaction> bankTransactions = bankTransactionRepository.findTransactionByAccountAndDatetimeGreaterThanOrderByDatetimeDesc(accountName, thatDay);

    BigDecimal sum = base.get(accountName);

    for (BankTransaction bankTransaction : bankTransactions) {
      sum = sum.subtract(bankTransaction.getAmount()).subtract(bankTransaction.getFee());
    }

    return sum;
  }


  public List<BankTransactionDto> historyBetweenDates(Timestamp startTimestamp, Timestamp endTimestamp, String account) {
    List<BankTransaction> bankTransactions = bankTransactionRepository.findByDatetimeBetweenAndAccount(startTimestamp, endTimestamp, account);
    return bankTransactionMapper.toDto(bankTransactions);
  }

  private BigDecimal fillGapsRecursive(int index, List<String> daysList, Map<String, BigDecimal> points, BigDecimal lastDayValue) {
    if (index < daysList.size()) {
      String today = daysList.get(index);
      BigDecimal value = points.get(today);

      if (value == null) {
        value = fillGapsRecursive(index + 1, daysList, points, lastDayValue);
      }

      fillGapsRecursive(index + 1, daysList, points, lastDayValue);
      points.put(today, value);
      return value;
    } else {
      return lastDayValue;
    }
  }

  private void fillGaps(List<String> daysList, Map<String, BigDecimal> points) {
    BigDecimal lastDayValue = null;
    for(int i = daysList.size() - 1; i >= 0; i--){
      String lastDay = daysList.get(i);
      lastDayValue = points.get(lastDay);
      if(lastDayValue != null)
        break;
    }

    fillGapsRecursive(0, daysList, points, lastDayValue);
  }

  public GraphPointsDto historyBetweenDates(Timestamp startTimestamp, Timestamp endTimestamp) {
    GraphPointsDto result = new GraphPointsDto();
    List<String> daysList = DateUtils.getAllDaysBetweenTimestamps(startTimestamp, endTimestamp);
    List<String> accounts = bankTransactionRepository.findDistinctAccounts();

    result.setXLabels(daysList);
    for (String account : accounts) {
      Map<String, BigDecimal> points = new HashMap<>();
      List<BankTransaction> bankTransactions = bankTransactionRepository.findByDatetimeBetweenAndAccountOrderByDatetime(startTimestamp, endTimestamp, account);
      for (BankTransaction bankTransaction : bankTransactions) {
        String simpleDate = DateUtils.convertTimestampToString(bankTransaction.getDatetime());
        points.put(simpleDate, bankTransaction.getCumulativeAmount());
      }
      result.getLineNames().add(account);
      fillGaps(daysList, points);
      result.getData().put(account, points);
    }

    return result;
  }

  public List<BankTransactionDto> addTransactions(List<BankTransactionDto> bankTransactionDto) {
    List<BankTransaction> txToAdd = bankTransactionMapper.toEntity(bankTransactionDto);
    List<BankTransaction> txAdded = bankTransactionRepository.saveAll(txToAdd);
    bankTransactionRepository.flush();
    return bankTransactionMapper.toDto(txAdded);
  }

  private List<BankTransactionDto> revolutCsv(String csvData) throws IOException {
    BufferedReader reader = new BufferedReader(new StringReader(csvData));
    List<BankTransactionDto> bankTransactionDtos = new ArrayList<>();
    String line;
    // Read the header line to get field names
    String[] headers = reader.readLine().split(",");
    while ((line = reader.readLine()) != null) {
      String[] data = line.split(",", -1);
      if (data.length != headers.length)
        throw new RuntimeException("lol");

      // Create a Map to hold the data of each row
      Map<String, Object> rowData = new HashMap<>();
      for (int i = 0; i < headers.length; i++) {
        rowData.put(headers[i], data[i]);
      }

      BankTransactionDto bankTransactionDto = bankTransactionMapper.toDto(rowData);
      bankTransactionDtos.add(bankTransactionDto);
    }
    return bankTransactionDtos;
  }

  public List<BankTransactionDto> addTransactionsFromCsv(String csvData, BankName bankName) {
    if (csvData == null || csvData.isEmpty()) {
      throw new RuntimeException("CSV data is empty");
    }
    try {
      List<BankTransactionDto> bankTransactionDtos;
      switch (bankName) {
        case Revolut -> bankTransactionDtos = revolutCsv(csvData);
        case Sanpaolo -> throw new RuntimeException("Sanpaolo not implemented yet");
        default -> throw new RuntimeException("Impossible to be here");
      }

      return addTransactions(bankTransactionDtos);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read CSV data", e);
    } catch (Exception e) {
      // Handle any other exceptions that might occur during processing
      throw new RuntimeException("Error processing CSV data", e);
    }
  }

}

