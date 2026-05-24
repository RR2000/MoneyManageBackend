package com.rondinella.moneymanageapi.banktransactions;

import com.opencsv.CSVReader;
import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.common.configurations.AccountBaseProperties;
import com.rondinella.moneymanageapi.common.dtos.GraphPointsDto;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

@Service
public class BankTransactionService {
  public enum BankName {
    Degiro,
    Revolut,
    Sanpaolo
  }

  final BankTransactionRepository bankTransactionRepository;
  final DailyBalanceRepository dailyBalanceRepository;
  final AccountBaseProperties accountBaseProperties;
  BankTransactionMapper bankTransactionMapper = BankTransactionMapper.INSTANCE;

  public BankTransactionService(BankTransactionRepository bankTransactionRepository,
                                DailyBalanceRepository dailyBalanceRepository,
                                AccountBaseProperties accountBaseProperties) {
    this.bankTransactionRepository = bankTransactionRepository;
    this.dailyBalanceRepository = dailyBalanceRepository;
    this.accountBaseProperties = accountBaseProperties;
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

    BigDecimal cumulative = todayMoney;
    bankTransactions.get(0).setCumulativeAmount(cumulative);
    for (int i = 1; i < bankTransactions.size(); i++) {
      BankTransaction previous = bankTransactions.get(i - 1);
      BankTransaction bankTransaction = bankTransactions.get(i);
      cumulative = cumulative.subtract(previous.getAmount());
      bankTransaction.setCumulativeAmount(cumulative);
    }

    bankTransactionRepository.saveAllAndFlush(bankTransactions);

    // Populate daily balance snapshot table
    List<BankTransaction> ordered = bankTransactionRepository.findTransactionByAccountOrderByDatetime(account);
    Map<LocalDate, BigDecimal> dailyMap = new LinkedHashMap<>();
    for (BankTransaction tx : ordered) {
      LocalDate day = tx.getDatetime().toLocalDateTime().toLocalDate();
      dailyMap.put(day, tx.getCumulativeAmount());
    }
    List<DailyBalance> snapshots = new ArrayList<>();
    dailyMap.forEach((date, balance) -> snapshots.add(new DailyBalance(account, date, balance)));
    dailyBalanceRepository.saveAllAndFlush(snapshots);

    return true;
  }

  public BigDecimal amountOnThatDay(String accountName, Timestamp thatDay) {
    Map<String, BigDecimal> base = accountBaseProperties.getAmounts();
    List<BankTransaction> bankTransactions = bankTransactionRepository.findTransactionByAccountAndDatetimeGreaterThanOrderByDatetimeDesc(accountName, thatDay);
    BigDecimal sum = base.get(accountName);
    for (BankTransaction bankTransaction : bankTransactions) {
      sum = sum.subtract(bankTransaction.getAmount()).subtract(bankTransaction.getFee());
    }
    return sum;
  }

  public List<DailyBalance> getDailyBalance(String account) {
    return dailyBalanceRepository.findByAccountOrderByDate(account);
  }

  public List<BankTransactionDto> historyBetweenDates(Timestamp startTimestamp, Timestamp endTimestamp, String account) {
    List<BankTransaction> bankTransactions = bankTransactionRepository.findByDatetimeBetweenAndAccount(startTimestamp, endTimestamp, account);
    return bankTransactionMapper.toDto(bankTransactions);
  }

  public Map<String, BigDecimal> getDailyDepositSum(String account, Timestamp startTimestamp, Timestamp endTimestamp) {
    List<Object[]> results = bankTransactionRepository.findDailyDepositSumByAccountAndDateRange(account, startTimestamp, endTimestamp);
    Map<String, BigDecimal> dailyDepositSumMap = new LinkedHashMap<>();
    BigDecimal sum = BigDecimal.ZERO;
    for (Object[] result : results) {
      String day = Utils.convertDateToString((java.util.Date) result[0]);
      sum = sum.add((BigDecimal) result[1]);
      dailyDepositSumMap.put(day, sum);
    }
    return dailyDepositSumMap;
  }

  public GraphPointsDto historyBetweenDates(Timestamp startTimestamp, Timestamp endTimestamp) {
    GraphPointsDto result = new GraphPointsDto();
    List<String> accounts = bankTransactionRepository.findDistinctAccounts();

    LocalDate from = startTimestamp.toLocalDateTime().toLocalDate();
    LocalDate to = endTimestamp.toLocalDateTime().toLocalDate();

    for (String account : accounts) {
      List<DailyBalance> snapshots = dailyBalanceRepository.findByAccountAndDateBetweenOrderByDate(account, from, to);
      Map<String, BigDecimal> points = new LinkedHashMap<>();
      if (!snapshots.isEmpty()) {
        for (DailyBalance snapshot : snapshots) {
          points.put(snapshot.getDate().toString(), snapshot.getBalance());
        }
      } else {
        // Fallback: compute from raw transactions
        List<BankTransaction> bankTransactions = bankTransactionRepository.findByDatetimeBetweenAndAccountOrderByDatetime(startTimestamp, endTimestamp, account);
        for (BankTransaction bankTransaction : bankTransactions) {
          String simpleDate = Utils.convertTimestampToString(bankTransaction.getDatetime());
          points.put(simpleDate, bankTransaction.getCumulativeAmount());
        }
      }
      result.addPoints(account, points);
    }

    result.validateAndFillMissingValues();
    return result;
  }

  public List<BankTransactionDto> addTransactions(List<BankTransactionDto> bankTransactionDto) {
    List<BankTransaction> txToAdd = bankTransactionMapper.toEntity(bankTransactionDto);
    List<BankTransaction> txAdded = bankTransactionRepository.saveAll(txToAdd);
    bankTransactionRepository.flush();
    return bankTransactionMapper.toDto(txAdded);
  }

  private List<BankTransactionDto> revolutCsv(String csvData) throws IOException {
    List<BankTransactionDto> bankTransactionDtos = new ArrayList<>();
    try (CSVReader reader = new CSVReader(new StringReader(csvData))) {
      String[] headers = reader.readNext();
      if (headers == null) {
        return bankTransactionDtos;
      }
      String[] data;
      while ((data = reader.readNext()) != null) {
        if (data.length != headers.length)
          throw new RuntimeException("Revolut CSV row has " + data.length + " columns but header has " + headers.length);
        Map<String, Object> rowData = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
          rowData.put(headers[i], data[i]);
        }
        BankTransactionDto bankTransactionDto = bankTransactionMapper.toDtoFromRevolut(rowData);
        bankTransactionDtos.add(bankTransactionDto);
      }
    }
    return bankTransactionDtos;
  }

  public List<BankTransactionDto> degiroCsv(String csvData) throws IOException {
    List<BankTransactionDto> bankTransactionDtos = new ArrayList<>();
    try (CSVReader reader = new CSVReader(new StringReader(csvData))) {
      String[] headers = reader.readNext();
      for (int i = 0; i < headers.length; i++) {
        if (headers[i].isEmpty())
          headers[i] = String.valueOf(i);
      }
      String[] line;
      while ((line = reader.readNext()) != null) {
        Map<String, Object> rowData = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
          String value = (i < line.length) ? line[i] : "";
          rowData.put(headers[i], value);
        }
        if (((String) rowData.get("8")).isEmpty())
          continue;
        BankTransactionDto bankTransactionDto = bankTransactionMapper.toDtoFromDegiro(rowData);
        if (bankTransactionDto.getDescription().equals("Degiro Cash Sweep Transfer"))
          continue;
        bankTransactionDtos.add(bankTransactionDto);
      }
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
        case Degiro -> bankTransactionDtos = degiroCsv(csvData);
        case Revolut -> bankTransactionDtos = revolutCsv(csvData);
        case Sanpaolo -> throw new RuntimeException("Sanpaolo not implemented yet");
        default -> throw new RuntimeException("Impossible to be here");
      }
      return addTransactions(bankTransactionDtos);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read CSV data", e);
    } catch (Exception e) {
      throw new RuntimeException("Error processing CSV data", e);
    }
  }
}
