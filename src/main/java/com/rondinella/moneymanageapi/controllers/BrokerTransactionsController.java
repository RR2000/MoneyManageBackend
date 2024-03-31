package com.rondinella.moneymanageapi.controllers;

import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.dtos.GraphPointsDto;
import com.rondinella.moneymanageapi.services.BrokerTransactionService;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;

import java.math.BigDecimal;
import java.sql.Timestamp;

@RestController
@CrossOrigin(origins = "http://localhost:4200") // Allow requests from Angular app
@RequestMapping(path = "/api/brokers/transactions", produces = "application/json")
public class BrokerTransactionsController {
  final
  BrokerTransactionService brokerTransactionService;

  public BrokerTransactionsController(BrokerTransactionService brokerTransactionService) {
    this.brokerTransactionService = brokerTransactionService;
  }

  @GetMapping("/worth/graph")
  public GraphPointsDto worthGraph() {
    Timestamp f = Utils.stringToTimestamp("2021-01-01");
    Timestamp t = Utils.todayAsTimestamp();
    return brokerTransactionService.worthGraph(f, t);
  }

  @SneakyThrows
  @GetMapping("/luckySearch/{query}")
  public Stock luckySearch(@PathVariable String query) {
    String searchResult = YahooFinance.luckySearchTicker(query);
    return YahooFinance.get(searchResult);
  }

  @GetMapping("/worth")
  public BigDecimal worth() {
    return brokerTransactionService.worthRightNow();
  }

  @GetMapping("/worth/{timestamp}")
  public BigDecimal worthAtDatetime(@PathVariable String timestamp) {
    return brokerTransactionService.worthAtDatetime(Utils.stringToTimestamp(timestamp));
  }

  @PostMapping(value = "/upload", consumes = "text/csv")
  public ResponseEntity<?> upload(@RequestBody String csvData) {
    return ResponseEntity.status(HttpStatus.CREATED).body(brokerTransactionService.addTransactionsFromCsv(csvData, BrokerTransactionService.BrokerName.Degiro));
  }
}
