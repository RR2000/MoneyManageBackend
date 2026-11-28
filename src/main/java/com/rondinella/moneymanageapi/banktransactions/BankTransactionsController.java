package com.rondinella.moneymanageapi.banktransactions;

import com.rondinella.moneymanageapi.banktransactions.BankTransactionService.BankName;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

@RestController
@RequestMapping(path = "/api/banks/transactions", produces = "application/json")
public class BankTransactionsController {

  private final BankTransactionService bankTransactionService;

  public BankTransactionsController(BankTransactionService bankTransactionService) {
    this.bankTransactionService = bankTransactionService;
  }

  /** Today's balance of the account, in the body: an amount in the URL would end up in the access logs. */
  public record TodayBalance(BigDecimal amountToday) {
  }

  @PostMapping("/accounts/{accountName}/balance")
  public ResponseEntity<?> computeCumulativeAmount(@PathVariable String accountName, @RequestBody TodayBalance body) {
    if (body == null || body.amountToday() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amountToday is required");
    }
    return new ResponseEntity<>(bankTransactionService.computeCumulativeAmount(accountName, body.amountToday()), HttpStatus.OK);
  }

  @GetMapping("/accounts")
  public ResponseEntity<?> getAllAccounts() {
    return ResponseEntity.status(HttpStatus.OK).body(bankTransactionService.findAllAccounts());
  }

  @PostMapping(value = "/{bankName}/upload", consumes = "multipart/form-data")
  public ResponseEntity<?> uploadTransactions(@RequestParam MultipartFile multipartFile, @PathVariable BankName bankName) {
    return ResponseEntity.status(HttpStatus.CREATED).body(bankTransactionService.addTransactionsFromMultipartFile(multipartFile, bankName));
  }
}
