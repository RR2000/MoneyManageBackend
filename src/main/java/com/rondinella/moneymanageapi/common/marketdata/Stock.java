package com.rondinella.moneymanageapi.common.marketdata;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class Stock {
  private final String symbol;
  private String name;
  private String currency;
  private String stockExchange;
  private BigDecimal close;

  private List<HistoricalQuote> history;

}
