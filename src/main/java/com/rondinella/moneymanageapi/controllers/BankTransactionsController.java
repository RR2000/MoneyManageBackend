package com.rondinella.moneymanageapi.controllers;

import com.rondinella.moneymanageapi.dtos.BankTransactionDto;
import com.rondinella.moneymanageapi.services.BankTransactionService;
import com.rondinella.moneymanageapi.services.BankTransactionService.BankName;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

@RestController
@CrossOrigin(origins = "http://localhost:4200") // Allow requests from Angular app
@RequestMapping(path = "/api/banks/transactions", produces = "application/json")
public class BankTransactionsController {

  private final BankTransactionService bankTransactionService;

  public BankTransactionsController(BankTransactionService bankTransactionService) {
    this.bankTransactionService = bankTransactionService;
  }

  @GetMapping
  public ResponseEntity<?> getAllTransactions() {
    return new ResponseEntity<>(bankTransactionService.findAllTransactions(), HttpStatus.CREATED);
  }

  @GetMapping("/accounts/{accountName}")
  public ResponseEntity<?> getTransactionsByAccountName(@PathVariable String accountName) {
    return new ResponseEntity<>(bankTransactionService.findTransactionsByAccount(accountName), HttpStatus.OK);
  }

  @GetMapping("/accounts/{accountName}/computeCumulativeAmount/{amountToday}")
  public ResponseEntity<?> computeCumulativeAmount(@PathVariable String accountName, @PathVariable BigDecimal amountToday) {
    return new ResponseEntity<>(bankTransactionService.computeCumulativeAmount(accountName, amountToday), HttpStatus.OK);
  }

  @GetMapping("/accounts/{accountName}/{timestamp}")
  public ResponseEntity<?> getTransactionsByAccountName(@PathVariable String accountName, @PathVariable Timestamp timestamp) {
    return new ResponseEntity<>(bankTransactionService.amountOnThatDay(accountName, timestamp), HttpStatus.OK);
  }

  @GetMapping("graph/{fromTimestamp}/{toTimestamp}")
  public ResponseEntity<?> historyBetweenDatesGraph(
      @PathVariable @Parameter(example = "2024-03-01 00:00:00.000") Timestamp fromTimestamp,
      @PathVariable @Parameter(example = "2024-03-31 23:59:59.999") Timestamp toTimestamp) {
    return new ResponseEntity<>(bankTransactionService.historyBetweenDates(fromTimestamp, toTimestamp), HttpStatus.OK);
  }

  @GetMapping("table/{fromTimestamp}/{toTimestamp}")
  public ResponseEntity<?> historyBetweenDatesTable(
      @PathVariable @Parameter(example = "2024-03-01 00:00:00.000") Timestamp fromTimestamp,
      @PathVariable @Parameter(example = "2024-03-31 23:59:59.999") Timestamp toTimestamp,
      @RequestParam String accountName) {
    return new ResponseEntity<>(bankTransactionService.historyBetweenDates(fromTimestamp, toTimestamp, accountName), HttpStatus.OK);
  }

  @GetMapping("/accounts")
  public ResponseEntity<?> getAllAccounts() {
    return ResponseEntity.status(HttpStatus.OK).body(bankTransactionService.findAllAccounts());
  }

  @PostMapping
  public ResponseEntity<?> createTransaction(@RequestBody List<BankTransactionDto> transactionsDto) {
    return ResponseEntity.status(HttpStatus.CREATED).body(bankTransactionService.addTransactions(transactionsDto));
  }

  @PostMapping(value = "/{bankName}/upload", consumes = "text/csv")
  public ResponseEntity<?> uploadTransactions(@RequestBody String csvData, @PathVariable BankName bankName) {
    return ResponseEntity.status(HttpStatus.CREATED).body(bankTransactionService.addTransactionsFromCsv(csvData, bankName));
  }
}
