package com.rondinella.moneymanageapi.stocks;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.sql.Timestamp;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class StockId implements Serializable {
  private String symbol;
  private String isin;
  private Timestamp datetime;
}
