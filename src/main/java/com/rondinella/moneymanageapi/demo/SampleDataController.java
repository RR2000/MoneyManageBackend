package com.rondinella.moneymanageapi.demo;

import com.rondinella.moneymanageapi.banktransactions.BankTransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Sample data on request, for an account that started empty (for example one created with GitHub). */
@RestController
@RequestMapping(path = "/api/sample-data", produces = "application/json")
public class SampleDataController {

  private final SampleData sampleData;
  private final BankTransactionService bankTransactionService;

  public SampleDataController(SampleData sampleData, BankTransactionService bankTransactionService) {
    this.sampleData = sampleData;
    this.bankTransactionService = bankTransactionService;
  }

  @PostMapping
  public ResponseEntity<Void> load() {
    if (!bankTransactionService.findAllAccounts().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The account already has data");
    }
    sampleData.load();
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }
}
