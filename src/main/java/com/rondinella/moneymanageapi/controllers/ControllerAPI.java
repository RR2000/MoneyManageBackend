package com.rondinella.moneymanageapi.controllers;

import com.rondinella.moneymanageapi.dtos.TransactionDto;
import com.rondinella.moneymanageapi.services.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    return new ResponseEntity<>(transactionService.findAllTransactions(), HttpStatus.CREATED);
  }

  @GetMapping("/accounts/{accountName}")
  public ResponseEntity<?> getTransactionsByAccountName(@PathVariable String accountName) {
    return new ResponseEntity<>(transactionService.findTransactionsByAccount(accountName), HttpStatus.FOUND);
  }

  @GetMapping("/accounts")
  public ResponseEntity<?> getAllAccounts() {
    return ResponseEntity.status(HttpStatus.FOUND).body(transactionService.findAllAccounts());
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
