package com.rondinella.moneymanageapi.services;

import com.rondinella.moneymanageapi.dtos.AccountSummary;
import com.rondinella.moneymanageapi.dtos.TransactionDto;
import com.rondinella.moneymanageapi.enitities.Transaction;
import com.rondinella.moneymanageapi.mappers.TransactionMapper;
import com.rondinella.moneymanageapi.repositories.TransactionRepository;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TransactionService {
  public enum BankName {
    Revolut,
    Sanpaolo
  }

  final
  TransactionRepository transactionRepository;
  TransactionMapper transactionMapper = TransactionMapper.INSTANCE;

  public TransactionService(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  public List<TransactionDto> findAllTransactions() {
    return transactionMapper.toDto(transactionRepository.findAll());
  }

  public List<TransactionDto> findTransactionsByAccount(String accountName) {
    return transactionMapper.toDto(transactionRepository.findTransactionByAccountOrderByCompletedDate(accountName));
  }

  public List<String> findAllAccounts() {
    return transactionRepository.findDistinctAccounts();
  }

  public boolean computeCumulativeAmount(String account, BigDecimal todayMoney) {
    List<Transaction> transactions = transactionRepository.findTransactionByAccountOrderByCompletedDateDesc(account);

    BigDecimal cumulative = todayMoney.add(transactions.get(0).getAmount()); // Initialize with today's money
    for (Transaction transaction : transactions) {
      cumulative = cumulative.subtract(transaction.getAmount()); // Add current transaction's amount
      transaction.setCumulativeAmount(cumulative); // Set cumulative amount for the transaction
    }

    transactionRepository.saveAllAndFlush(transactions);

    return true;
  }

  public BigDecimal amountOnThatDay(String accountName, Timestamp thatDay) {
    Map<String, BigDecimal> base = new HashMap<>();
    base.put("Revolut_Current", new BigDecimal("0"));
    base.put("Revolut_Pocket", new BigDecimal("0"));
    base.put("Revolut_Savings", new BigDecimal("0"));

    List<Transaction> transactions = transactionRepository.findTransactionByAccountAndCompletedDateGreaterThanOrderByCompletedDateDesc(accountName, thatDay);

    BigDecimal sum = base.get(accountName);

    for (Transaction transaction : transactions) {
      sum = sum.subtract(transaction.getAmount()).subtract(transaction.getFee());
    }

    return sum;
  }

  public List<AccountSummary> historyBetweenDates(Timestamp startTimestamp, Timestamp endTimestamp) {
    List<Transaction> transactions = transactionRepository.findByCompletedDateBetween(startTimestamp, endTimestamp);

    Map<String, Map<Date, BigDecimal>> accountSummariesMap = new HashMap<>();

    LocalDate currentDate = startTimestamp.toLocalDateTime().toLocalDate();
    LocalDate endDate = endTimestamp.toLocalDateTime().toLocalDate();
    while (!currentDate.isAfter(endDate)) {
      for (String account : findAllAccounts()) {
        accountSummariesMap.computeIfAbsent(account, k -> new HashMap<>())
            .put(Date.valueOf(currentDate), BigDecimal.ZERO);
      }
      currentDate = currentDate.plusDays(1);
    }

    for (Transaction transaction : transactions) {
      String account = transaction.getAccount();
      LocalDate transactionDate = transaction.getCompletedDate().toLocalDateTime().toLocalDate();
      Date date = Date.valueOf(transactionDate);
      BigDecimal amount = transaction.getAmount().add(transaction.getFee());

      Map<Date, BigDecimal> accountSummary = accountSummariesMap.get(account);
      accountSummary.put(date, accountSummary.get(date).add(amount));
    }

    Map<String, BigDecimal> base = new HashMap<>();
    base.put("Revolut_Current", new BigDecimal("0"));
    base.put("Revolut_Pocket", new BigDecimal("0"));
    base.put("Revolut_Savings", new BigDecimal("0"));

    List<AccountSummary> accountSummaries = new ArrayList<>();
    for (Map.Entry<String, Map<Date, BigDecimal>> entry : accountSummariesMap.entrySet()) {
      String accountName = entry.getKey();
      Map<Date, BigDecimal> accountData = entry.getValue();
      List<AccountSummary.ValueDatePair> valueDatePairs = new ArrayList<>();
      for (Map.Entry<Date, BigDecimal> dataEntry : accountData.entrySet()) {
        valueDatePairs.add(new AccountSummary.ValueDatePair(dataEntry.getKey(), dataEntry.getValue()));
      }
      AccountSummary accountSummary = new AccountSummary(accountName, valueDatePairs);
      accountSummary.sortAccountsByDate();

      BigDecimal previousValue = base.get(accountName);

      List<AccountSummary.ValueDatePair> sortedValueDatePairs = accountSummary.getSummary();

      for (AccountSummary.ValueDatePair valuePair : sortedValueDatePairs) {
        BigDecimal currentValue = valuePair.getValue();
        valuePair.setValue(currentValue.add(previousValue));
        previousValue = valuePair.getValue();
      }

      accountSummary.setSummary(sortedValueDatePairs);
      accountSummaries.add(accountSummary);
    }

    return accountSummaries;
  }

  public List<TransactionDto> addTransactions(List<TransactionDto> transactionDto) {
    List<Transaction> txToAdd = transactionMapper.toEntity(transactionDto);
    List<Transaction> txAdded = transactionRepository.saveAll(txToAdd);
    transactionRepository.flush();
    return transactionMapper.toDto(txAdded);
  }

  private List<TransactionDto> revolutCsv(String csvData) throws IOException {
    BufferedReader reader = new BufferedReader(new StringReader(csvData));
    List<TransactionDto> transactionDtos = new ArrayList<>();
    String line;
    // Read the header line to get field names
    String[] headers = reader.readLine().split(",");
    while ((line = reader.readLine()) != null) {
      String[] data = line.split(",", -1);
      if(data.length != headers.length)
        throw new RuntimeException("lol");

      // Create a Map to hold the data of each row
      Map<String, Object> rowData = new HashMap<>();
      for (int i = 0; i < headers.length; i++) {
        rowData.put(headers[i], data[i]);
      }

      TransactionDto transactionDto = transactionMapper.toDto(rowData);
      transactionDtos.add(transactionDto);
    }
    return transactionDtos;
  }

  public List<TransactionDto> addTransactionsFromCsv(String csvData, BankName bankName) {
    if (csvData == null || csvData.isEmpty()) {
      throw new RuntimeException("CSV data is empty");
    }
    try {
      List<TransactionDto> transactionDtos;
      switch (bankName) {
        case Revolut -> transactionDtos = revolutCsv(csvData);
        case Sanpaolo -> throw new RuntimeException("Sanpaolo not implemented yet");
        default -> throw new RuntimeException("Impossible to be here");
      }

      return addTransactions(transactionDtos);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read CSV data", e);
    } catch (Exception e) {
      // Handle any other exceptions that might occur during processing
      throw new RuntimeException("Error processing CSV data", e);
    }
  }

}
