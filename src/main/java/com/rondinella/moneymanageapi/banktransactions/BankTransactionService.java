package com.rondinella.moneymanageapi.banktransactions;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import com.rondinella.moneymanageapi.brokertransactions.DegiroCsv;
import com.rondinella.moneymanageapi.categories.Categorizer;
import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.common.dtos.GraphPointsDto;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
  final Categorizer categorizer;
  BankTransactionMapper bankTransactionMapper = BankTransactionMapper.INSTANCE;

  public BankTransactionService(BankTransactionRepository bankTransactionRepository,
                                DailyBalanceRepository dailyBalanceRepository, Categorizer categorizer) {
    this.bankTransactionRepository = bankTransactionRepository;
    this.dailyBalanceRepository = dailyBalanceRepository;
    this.categorizer = categorizer;
  }

  public List<BankTransactionDto> findAllTransactions() {
    return bankTransactionMapper.toDto(bankTransactionRepository.findAll());
  }

  public List<BankTransactionDto> findTransactionsByAccount(String accountName) {
    return bankTransactionMapper.toDto(bankTransactionRepository.findTransactionByAccountOrderByDatetimeAscIdAsc(accountName));
  }

  public List<String> findAllAccounts() {
    return bankTransactionRepository.findDistinctAccounts();
  }

  /**
   * The balance after every movement, back from today's balance, and the balance of every day (daily_balance): the
   * days of the account are written again from scratch, so a day whose movements were deleted does not keep its old
   * balance. In one transaction.
   */
  @Transactional
  public boolean computeCumulativeAmount(String account, BigDecimal todayMoney) {
    List<BankTransaction> bankTransactions = bankTransactionRepository.findTransactionByAccountOrderByDatetimeDescIdDesc(account);
    if (bankTransactions.isEmpty()) {
      dailyBalanceRepository.deleteAllOfAccount(account);
      return false;
    }

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
    List<BankTransaction> ordered = bankTransactionRepository.findTransactionByAccountOrderByDatetimeAscIdAsc(account);
    Map<LocalDate, BigDecimal> dailyMap = new LinkedHashMap<>();
    for (BankTransaction tx : ordered) {
      LocalDate day = tx.getDatetime().toLocalDateTime().toLocalDate();
      dailyMap.put(day, tx.getCumulativeAmount());
    }
    List<DailyBalance> snapshots = new ArrayList<>();
    dailyMap.forEach((date, balance) -> snapshots.add(new DailyBalance(account, date, balance)));
    dailyBalanceRepository.deleteAllOfAccount(account);
    dailyBalanceRepository.saveAllAndFlush(snapshots);

    return true;
  }

  /** Balance of the account at the end of that day: the cumulative amount of its last transaction until then. */
  public BigDecimal amountOnThatDay(String accountName, Timestamp thatDay) {
    BankTransaction last = bankTransactionRepository.findFirstByAccountAndDatetimeLessThanEqualOrderByDatetimeDescIdDesc(accountName, thatDay);
    return last == null || last.getCumulativeAmount() == null ? BigDecimal.ZERO : last.getCumulativeAmount();
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
    Utils.requireSaneRange(startTimestamp, endTimestamp);
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
        List<BankTransaction> bankTransactions = bankTransactionRepository.findByDatetimeBetweenAndAccountOrderByDatetimeAscIdAsc(startTimestamp, endTimestamp, account);
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

  /** Movements typed or sent by the user (source MANUAL). See {@link #addTransactions(List, BankTransaction.Source)}. */
  public List<BankTransactionDto> addTransactions(List<BankTransactionDto> bankTransactionDto) {
    return addTransactions(bankTransactionDto, BankTransaction.Source.MANUAL);
  }

  /**
   * Adds movements without doubling what is already there. Movements are compared by account, moment and description,
   * counting them: if the list has n movements with the same three and the account already has m, the first min(n, m)
   * existing rows are updated (amount, fee, currency, as before the surrogate key) and only n - m are added. So the same
   * file imported twice gives the same rows, and two equal movements of the same day in one file are both kept (before,
   * the second overwrote the first). A row matched to a bank movement keeps its bank id and its category; the rows
   * added get the category of their rule (see {@link Categorizer}).
   */
  public List<BankTransactionDto> addTransactions(List<BankTransactionDto> bankTransactionDto, BankTransaction.Source source) {
    List<BankTransaction> incoming = bankTransactionMapper.toEntity(bankTransactionDto);
    Map<String, List<BankTransaction>> byAccount = new LinkedHashMap<>();
    for (BankTransaction tx : incoming) {
      if (tx.getAccount() == null || tx.getDatetime() == null || tx.getDescription() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "account, datetime and description are required");
      }
      tx.setId(null);
      tx.setExternalRef(null);
      tx.setSource(source);
      byAccount.computeIfAbsent(tx.getAccount(), a -> new ArrayList<>()).add(tx);
    }
    List<BankTransaction> toSave = new ArrayList<>();
    byAccount.forEach((account, txs) -> {
      Timestamp from = txs.stream().map(BankTransaction::getDatetime).min(Comparator.naturalOrder()).orElseThrow();
      Timestamp to = txs.stream().map(BankTransaction::getDatetime).max(Comparator.naturalOrder()).orElseThrow();
      Map<List<Object>, Deque<BankTransaction>> existing = new HashMap<>();
      for (BankTransaction row : bankTransactionRepository.findByAccountAndDatetimeBetweenOrderByIdAsc(account, from, to)) {
        existing.computeIfAbsent(key(row), k -> new ArrayDeque<>()).add(row);
      }
      for (BankTransaction tx : txs) {
        Deque<BankTransaction> same = existing.get(key(tx));
        BankTransaction row = same == null ? null : same.poll();
        if (row == null) {
          toSave.add(tx);
        } else {
          row.setAmount(tx.getAmount());
          row.setFee(tx.getFee());
          row.setCurrency(tx.getCurrency());
          if (tx.getCumulativeAmount() != null) {
            row.setCumulativeAmount(tx.getCumulativeAmount());
          }
          toSave.add(row);
        }
      }
    });
    categorizer.categorize(toSave.stream().filter(t -> t.getId() == null).toList());
    List<BankTransaction> saved = bankTransactionRepository.saveAllAndFlush(toSave);
    return bankTransactionMapper.toDto(saved);
  }

  private static List<Object> key(BankTransaction tx) {
    // the same instant whatever the Timestamp subclass or nanos the row was read with
    return List.of(tx.getDatetime().toInstant(), tx.getDescription());
  }

  static {
    // an .xlsx is a zip: no entry may inflate beyond 100 MB (zip bomb), the ratio check of POI stays on
    org.apache.poi.openxml4j.util.ZipSecureFile.setMaxEntrySize(100L * 1024 * 1024);
  }

  private List<BankTransactionDto> revolutCsv(MultipartFile file) throws IOException, CsvValidationException {
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

  /**
   * Degiro's account statement (Account.csv), read by column name in any of its languages, each row with its own
   * currency (dividends and withholdings in USD stay in USD). All in the account "Degiro", as before.
   */
  public List<BankTransactionDto> degiroCsv(MultipartFile file) throws IOException {
    List<BankTransactionDto> bankTransactionDtos = new ArrayList<>();
    for (DegiroCsv.CashMovement m : DegiroCsv.account(new String(file.getBytes(), StandardCharsets.UTF_8))) {
      BankTransactionDto dto = new BankTransactionDto();
      dto.setAccount("Degiro");
      dto.setDatetime(m.datetime());
      dto.setDescription(m.description());
      dto.setAmount(m.amount());
      dto.setFee(BigDecimal.ZERO);
      dto.setCurrency(m.currency());
      bankTransactionDtos.add(dto);
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
      return addTransactions(bankTransactionDtos, BankTransaction.Source.FILE);
    } catch (Exception e) {
      // unreadable file or a format that does not match the bank: the user's fault, not a server error
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot read the " + bankName + " file: " + e.getMessage(), e);
    }
  }
}
