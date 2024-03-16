package com.rondinella.moneymanageapi.services;

import com.rondinella.moneymanageapi.dtos.TransactionDto;
import com.rondinella.moneymanageapi.enitities.Transaction;
import com.rondinella.moneymanageapi.mappers.TransactionMapper;
import com.rondinella.moneymanageapi.repositories.TransactionRepository;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TransactionService {
  final
  TransactionRepository transactionRepository;
  TransactionMapper transactionMapper = TransactionMapper.INSTANCE;

  public TransactionService(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  public List<TransactionDto> findAllTransactions() {
    return transactionMapper.toDto(transactionRepository.findAll());
  }

  public List<TransactionDto> addTransactions(List<TransactionDto> transactionDto) {
    List<Transaction> txToAdd = transactionMapper.toEntity(transactionDto);
    List<Transaction> txAdded = transactionRepository.saveAllAndFlush(txToAdd);
    return transactionMapper.toDto(txAdded);
  }

  public List<TransactionDto> addTransactionsFromCsv(String csvData) {
    // Check if the CSV data is empty
    if (csvData == null || csvData.isEmpty()) {
      throw new RuntimeException("CSV data is empty");
    }
    try {
      BufferedReader reader = new BufferedReader(new StringReader(csvData));
      List<TransactionDto> transactionDtos = new ArrayList<>();
      String line;
      // Read the header line to get field names
      String[] headers = reader.readLine().split(",");
      while ((line = reader.readLine()) != null) {
        String[] data = line.split(",");
        // Create a Map to hold the data of each row
        Map<String, Object> rowData = new HashMap<>();
        try {
          for (int i = 0; i < headers.length; i++) {
            // Assuming the first column contains id, the second column contains name, etc.
            rowData.put(headers[i], data[i]);
          }
        }catch (ArrayIndexOutOfBoundsException e){
          if(rowData.get("State").equals("PENDING"))
            continue;
        }

        // Create a TransactionDto from the row data Map
        TransactionDto transactionDto = transactionMapper.toDto(rowData);
        transactionDtos.add(transactionDto);
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
