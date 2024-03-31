package com.rondinella.moneymanageapi.common.marketdata;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Calendar;

@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
public class HistoricalQuote {
  private String symbol;

  private Calendar date;

  private BigDecimal open;
  private BigDecimal low;
  private BigDecimal high;
  private BigDecimal close;

  private BigDecimal adjClose;

  private Long volume;
}
