package com.rondinella.moneymanageapi.banktransactions;

import com.opencsv.CSVReader;
import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.common.configurations.AccountBaseProperties;
import com.rondinella.moneymanageapi.common.dtos.GraphPointsDto;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
    Sanpaolo,
    Unicredit
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
    List<String> daysList = Utils.getAllDaysBetweenTimestamps(startTimestamp, endTimestamp);
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

    result.addTotalColumn("Total cash", accounts);
    result.SetLabelsAndFillMissingValues(daysList);
    return result;
  }

  public List<BankTransactionDto> addTransactions(List<BankTransactionDto> bankTransactionDto) {
    List<BankTransaction> txToAdd = bankTransactionMapper.toEntity(bankTransactionDto);
    List<BankTransaction> txAdded = bankTransactionRepository.saveAll(txToAdd);
    bankTransactionRepository.flush();
    return bankTransactionMapper.toDto(txAdded);
  }

  private List<BankTransactionDto> revolutCsv(MultipartFile file) throws IOException {
    String csvData = new String(file.getBytes());
    List<BankTransactionDto> bankTransactionDtos = new ArrayList<>();
    try (CSVReader reader = new CSVReader(new StringReader(csvData))) {
      String[] headers = reader.readNext();
      if (headers == null) return bankTransactionDtos;
      String[] data;
      while ((data = reader.readNext()) != null) {
        if (data.length != headers.length)
          throw new RuntimeException("Revolut CSV row has " + data.length + " columns but header has " + headers.length);
        Map<String, Object> rowData = new HashMap<>();
        for (int i = 0; i < headers.length; i++) rowData.put(headers[i], data[i]);
        bankTransactionDtos.add(bankTransactionMapper.toDtoFromRevolut(rowData));
      }
    }
    return bankTransactionDtos;
  }

  public List<BankTransactionDto> degiroCsv(MultipartFile file) throws IOException {
    String csvData = new String(file.getBytes());
    List<BankTransactionDto> bankTransactionDtos = new ArrayList<>();
    try (CSVReader reader = new CSVReader(new StringReader(csvData))) {
      String[] headers = reader.readNext();
      for (int i = 0; i < headers.length; i++) {
        if (headers[i].isEmpty()) headers[i] = String.valueOf(i);
      }
      String[] line;
      while ((line = reader.readNext()) != null) {
        Map<String, Object> rowData = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
          rowData.put(headers[i], (i < line.length) ? line[i] : "");
        }
        if (((String) rowData.get("8")).isEmpty()) continue;
        BankTransactionDto dto = bankTransactionMapper.toDtoFromDegiro(rowData);
        if (dto.getDescription().equals("Degiro Cash Sweep Transfer")) continue;
        bankTransactionDtos.add(dto);
      }
    }
    return bankTransactionDtos;
  }

  public List<BankTransactionDto> xlsxSanpaolo(MultipartFile file) {
    List<BankTransactionDto> transactions = new ArrayList<>();
    try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
      Sheet sheet = workbook.getSheetAt(0);
      for (int i = 19; i <= sheet.getLastRowNum(); i++) {
        Row row = sheet.getRow(i);
        if (row != null && row.getCell(6) != null && !row.getCell(6).getStringCellValue().isEmpty()) {
          java.sql.Date data = Utils.convertJavaToSqlDate(row.getCell(0).getDateCellValue());
          String conto = row.getCell(3).getStringCellValue();
          BigDecimal importo = BigDecimal.valueOf(row.getCell(7).getNumericCellValue());
          Currency valuta = Currency.getInstance(row.getCell(6).getStringCellValue());

          BankTransactionDto dto = new BankTransactionDto();
          dto.setAccount(conto.replace(" ", "_").replace("/", "_"));
          dto.setDatetime(new Timestamp(data.getTime()));
          dto.setDescription(row.getCell(1).getStringCellValue());
          dto.setAmount(importo);
          dto.setFee(BigDecimal.ZERO);
          dto.setCurrency(valuta.getCurrencyCode());
          transactions.add(dto);
        }
      }
    } catch (IOException e) {
      throw new RuntimeException("Failed to read Sanpaolo XLSX", e);
    }
    return transactions;
  }

  private List<BankTransactionDto> unicreditCsv(MultipartFile file) throws IOException {
    String csvData = new String(file.getBytes());
    List<BankTransactionDto> bankTransactionDtos = new ArrayList<>();
    String[] lines = csvData.split("\n");
    if (lines.length < 2) return bankTransactionDtos;
    String[] headers = lines[0].split(";");
    for (int i = 1; i < lines.length; i++) {
      String[] data = lines[i].split(";", -1);
      if (data.length != headers.length) throw new RuntimeException("Unicredit CSV row length mismatch");
      Map<String, Object> rowData = new HashMap<>();
      for (int j = 0; j < headers.length; j++) rowData.put(headers[j], data[j]);
      bankTransactionDtos.add(bankTransactionMapper.toDtoFromUnicredit(rowData));
    }
    return bankTransactionDtos;
  }

  public List<BankTransactionDto> addTransactionsFromMultipartFile(MultipartFile file, BankName bankName) {
    try {
      List<BankTransactionDto> bankTransactionDtos;
      switch (bankName) {
        case Degiro -> bankTransactionDtos = degiroCsv(file);
        case Revolut -> bankTransactionDtos = revolutCsv(file);
        case Sanpaolo -> bankTransactionDtos = xlsxSanpaolo(file);
        case Unicredit -> bankTransactionDtos = unicreditCsv(file);
        default -> throw new RuntimeException("Unsupported bank: " + bankName);
      }
      return addTransactions(bankTransactionDtos);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read file", e);
    } catch (Exception e) {
      throw new RuntimeException("Error processing file", e);
    }
  }
}
