package com.rondinella.moneymanageapi.banktransactions;

import com.opencsv.CSVReader;
import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.common.dtos.GraphPointsDto;
import jakarta.persistence.Id;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

@Service
public class BankTransactionService {
  public enum BankName {
    Degiro,
    Revolut,
    Sanpaolo,
    Unicredit
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
    base.put("Revolut_Current", new BigDecimal("94.24"));
    base.put("Revolut_Pocket", new BigDecimal("79.86"));
    base.put("Revolut_Savings", new BigDecimal("98.16"));

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

  public Map<String, BigDecimal> getDailyDepositSum(String account, Timestamp startTimestamp, Timestamp endTimestamp) {
    List<Object[]> results = bankTransactionRepository.findDailyDepositSumByAccountAndDateRange(account, startTimestamp, endTimestamp);
    Map<String, BigDecimal> dailyDepositSumMap = new LinkedHashMap<>();

    // Iterate over the results and add the deposit sum for each day to the map
    BigDecimal sum = BigDecimal.ZERO;
    for (Object[] result : results) {
      String day = Utils.convertDateToString((Date) result[0]);
      sum = sum.add((BigDecimal) result[1]);
      dailyDepositSumMap.put(day, sum);
    }

    return dailyDepositSumMap;
  }

  public GraphPointsDto historyBetweenDates(Timestamp startTimestamp, Timestamp endTimestamp) {
    GraphPointsDto result = new GraphPointsDto();
    List<String> daysList = Utils.getAllDaysBetweenTimestamps(startTimestamp, endTimestamp);
    List<String> accounts = bankTransactionRepository.findDistinctAccounts();

    for (String account : accounts) {
      Map<String, BigDecimal> points = new HashMap<>();
      ArrayList<BankTransaction> bankTransactions = new ArrayList<>();
      bankTransactions.add(bankTransactionRepository.findFirstBeforeStartTimestamp(startTimestamp, account));
      bankTransactions.addAll(bankTransactionRepository.findByDatetimeBetweenAndAccountOrderByDatetime(startTimestamp, endTimestamp, account));
      bankTransactions.add(bankTransactionRepository.findFirstAfterEndTimestamp(endTimestamp, account));
      for (BankTransaction bankTransaction : bankTransactions) {
        if(bankTransaction != null) {
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

      BankTransactionDto bankTransactionDto = bankTransactionMapper.toDtoFromRevolut(rowData);
      bankTransactionDtos.add(bankTransactionDto);
    }
    return bankTransactionDtos;
  }

  public List<BankTransactionDto> degiroCsv(MultipartFile file) throws IOException {
    String csvData = new String(file.getBytes());
    List<BankTransactionDto> bankTransactionDtos = new ArrayList<>();

    String[] headers;
    Map<String, Object> rowData;

    try (CSVReader reader = new CSVReader(new StringReader(csvData))) {
      headers = reader.readNext();
      for (int i = 0; i < headers.length; i++) {
        if (headers[i].isEmpty())
          headers[i] = String.valueOf(i);
      }

      String[] line;
      while ((line = reader.readNext()) != null) {
        rowData = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
          String value = (i < line.length) ? line[i] : ""; // Handle missing values
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


  public List<BankTransactionDto> xlsxSanpaolo(MultipartFile file) {
    List<BankTransactionDto> transactions = new ArrayList<>();
    try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
      Sheet sheet = workbook.getSheetAt(0); // Assuming data is in the first sheet
      for (int i = 19; i <= sheet.getLastRowNum(); i++) {
        Row row = sheet.getRow(i);
        if (row != null && row.getCell(6) != null && !row.getCell(6).getStringCellValue().isEmpty()) {
          Date data = Utils.convertJavaToSqlDate(row.getCell(0).getDateCellValue());
          String operazione = row.getCell(1).getStringCellValue();
          String dettagli = row.getCell(2).getStringCellValue();
          String conto = row.getCell(3).getStringCellValue();
          String contabilizzazione = row.getCell(4).getStringCellValue();
          String categoria = row.getCell(5).getStringCellValue();
          Currency valuta = Currency.getInstance(row.getCell(6).getStringCellValue());
          BigDecimal importo = BigDecimal.valueOf(row.getCell(7).getNumericCellValue());

          BankTransactionDto bankTransactionDto = new BankTransactionDto();
          bankTransactionDto.setAccount(conto.replace(" ", "_").replace("/", "_"));
          bankTransactionDto.setDatetime(new Timestamp(data.getTime()));
          bankTransactionDto.setDescription(operazione);
          bankTransactionDto.setAmount(importo);
          bankTransactionDto.setFee(BigDecimal.ZERO);
          bankTransactionDto.setCurrency(valuta.getCurrencyCode());

          transactions.add(bankTransactionDto);
        }
      }
    } catch (IOException e) {
      e.printStackTrace(); // Handle the exception appropriately
    }
    return transactions;
  }


  //To do: Managment Unicredit CSV, in Development
  private List<BankTransactionDto> unicreditCsv(MultipartFile file) throws IOException {
    String csvData = new String(file.getBytes());
    BufferedReader reader = new BufferedReader(new StringReader(csvData));
    List<BankTransactionDto> bankTransactionDtos = new ArrayList<>();
    String line;
    // Read the header line to get field names
    String[] headers = reader.readLine().split(";");
    while ((line = reader.readLine()) != null) {
      String[] data = line.split(";", -1);
      if (data.length != headers.length)
        throw new RuntimeException("lol");

      // Create a Map to hold the data of each row
      Map<String, Object> rowData = new HashMap<>();
      for (int i = 0; i < headers.length; i++) {
        rowData.put(headers[i], data[i]);
      }

      BankTransactionDto bankTransactionDto = bankTransactionMapper.toDtoFromUnicredit(rowData);
      bankTransactionDtos.add(bankTransactionDto);
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
        case Unicredit -> bankTransactionDtos = unicreditCsv(file); // In Develop
        default -> throw new RuntimeException("Impossible to be here");
      }


      return addTransactions(bankTransactionDtos);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read data", e);
    } catch (Exception e) {
      throw new RuntimeException("Error processing data", e);
    }
  }

}

