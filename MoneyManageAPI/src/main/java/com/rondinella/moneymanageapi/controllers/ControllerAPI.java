package com.rondinella.moneymanageapi.controllers;

import com.rondinella.moneymanageapi.dtos.TransactionDto;
import com.rondinella.moneymanageapi.services.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ControllerAPI {

  private final TransactionService transactionService;

  public ControllerAPI(TransactionService transactionService) {
    this.transactionService = transactionService;
  }

  @GetMapping("/findAllTransactions")
  public ResponseEntity<?> findAllTransactions() {
    return ResponseEntity.ok(transactionService.findAllTransactions());
  }

  @PostMapping("/insertTransaction")
  public ResponseEntity<?> insertTransaction(@RequestBody TransactionDto transactionDto) {
    return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.addTransaction(transactionDto));
  }
}
