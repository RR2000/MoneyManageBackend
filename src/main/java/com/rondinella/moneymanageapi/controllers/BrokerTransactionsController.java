package com.rondinella.moneymanageapi.controllers;

import org.springframework.web.bind.annotation.*;
import yahoofinance.Stock;
import yahoofinance.YahooFinance;

import java.io.IOException;
import java.math.BigDecimal;

@RestController
@CrossOrigin(origins = "http://localhost:4200") // Allow requests from Angular app
@RequestMapping("/api/brokers/transactions")
public class BrokerTransactionsController {
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
}
