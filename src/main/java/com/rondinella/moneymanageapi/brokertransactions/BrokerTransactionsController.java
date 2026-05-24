package com.rondinella.moneymanageapi.brokertransactions;

import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.common.dtos.GraphPointsDto;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;

import java.math.BigDecimal;
import java.sql.Timestamp;

@RestController
@RequestMapping(path = "/api/brokers/transactions", produces = "application/json")
public class BrokerTransactionsController {
  final
  BrokerTransactionService brokerTransactionService;

  public BrokerTransactionsController(BrokerTransactionService brokerTransactionService) {
    this.brokerTransactionService = brokerTransactionService;
  }

  @GetMapping("/worth/graph")
  public GraphPointsDto worthGraph(
      @RequestParam(required = false) Timestamp from,
      @RequestParam(required = false) Timestamp to) {
    Timestamp f = from != null ? from : Utils.stringToTimestamp("2021-01-01");
    Timestamp t = to != null ? to : Utils.todayAsTimestamp();
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
