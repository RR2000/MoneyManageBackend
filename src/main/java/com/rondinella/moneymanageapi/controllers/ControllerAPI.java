package com.rondinella.moneymanageapi.controllers;

import com.rondinella.moneymanageapi.dtos.TransactionDto;
import com.rondinella.moneymanageapi.services.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class ControllerAPI {

  private final TransactionService transactionService;

  public ControllerAPI(TransactionService transactionService) {
    this.transactionService = transactionService;
  }

  @GetMapping
  public ResponseEntity<?> getAllTransactions() {
    return ResponseEntity.ok(transactionService.findAllTransactions());
  }

  @PostMapping
  public ResponseEntity<?> createTransaction(@RequestBody List<TransactionDto> transactionsDto) {
    return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.addTransaction(transactionsDto));
  }

  @PostMapping("/upload")
  public ResponseEntity<?> uploadTransactions(@RequestParam("file") MultipartFile file) {
    if (file.isEmpty()) {
      return ResponseEntity.badRequest().body("Please upload a file.");
    }
    return null;/*
    try {
      List<TransactionDto> transactions = transactionService.readTransactionsFromCSV(file);
      // Save transactions to the database
      for (TransactionDto transaction : transactions) {
        transactionService.addTransaction(transaction);
      }
      return ResponseEntity.status(HttpStatus.CREATED).body("Transactions uploaded successfully.");
    } catch (IOException e) {
      e.printStackTrace();
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to parse CSV file.");
    }*/
  }
}
