package com.rondinella.moneymanageapi.brokertransactions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/brokers/transactions", produces = "application/json")
public class BrokerTransactionsController {
  final
  BrokerTransactionService brokerTransactionService;

  public BrokerTransactionsController(BrokerTransactionService brokerTransactionService) {
    this.brokerTransactionService = brokerTransactionService;
  }

  @PostMapping(value = "/upload", consumes = "text/csv")
  public ResponseEntity<?> upload(@RequestBody String csvData) {
    return ResponseEntity.status(HttpStatus.CREATED).body(brokerTransactionService.addTransactionsFromCsv(csvData, BrokerTransactionService.BrokerName.Degiro));
  }
}
