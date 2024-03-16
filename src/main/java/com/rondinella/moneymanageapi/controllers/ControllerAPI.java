package com.rondinella.moneymanageapi.controllers;

import com.rondinella.moneymanageapi.dtos.TransactionDto;
import com.rondinella.moneymanageapi.services.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
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
    return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.addTransactions(transactionsDto));
  }

  @PostMapping(value = "/upload", consumes = "text/csv")
  public ResponseEntity<?> uploadTransactions(@RequestBody String csvData) {
    return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.addTransactionsFromCsv(csvData));
  }
}
