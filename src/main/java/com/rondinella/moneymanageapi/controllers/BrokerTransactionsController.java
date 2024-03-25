package com.rondinella.moneymanageapi.controllers;

import com.rondinella.moneymanageapi.services.BankTransactionService;
import com.rondinella.moneymanageapi.services.BrokerTransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;

import java.io.IOException;
import java.math.BigDecimal;

@RestController
@CrossOrigin(origins = "http://localhost:4200") // Allow requests from Angular app
@RequestMapping("/api/brokerTransactions")
public class BrokerTransactionsController {
  final
  BrokerTransactionService brokerTransactionService;

  public BrokerTransactionsController(BrokerTransactionService brokerTransactionService) {
    this.brokerTransactionService = brokerTransactionService;
  }

  @GetMapping("/luckySearch/{query}")
  public Stock getTransactionsByAccountName(@PathVariable String query) throws IOException {
    String searchResult =  YahooFinance.luckySearchTicker(query);
    Stock stock = YahooFinance.get(searchResult);

    BigDecimal price = stock.getQuote().getPrice();
    BigDecimal change = stock.getQuote().getChangeInPercent();
    BigDecimal peg = stock.getStats().getPeg();
    BigDecimal dividend = stock.getDividend().getAnnualYieldPercent();

    return stock;
  }

  @PostMapping(value = "/upload", consumes = "text/csv")
  public ResponseEntity<?> uploadTransactions(@RequestBody String csvData) {
    return ResponseEntity.status(HttpStatus.CREATED).body(brokerTransactionService.addTransactionsFromCsv(csvData, BrokerTransactionService.BrokerName.Degiro));
  }
}
