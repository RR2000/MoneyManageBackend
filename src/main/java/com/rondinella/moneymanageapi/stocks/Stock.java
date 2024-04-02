package com.rondinella.moneymanageapi.stocks;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Currency;

@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
@Entity
@IdClass(StockId.class)
public class Stock {
  @Id
  private String symbol;
  @Id
  private String isin;
  @Id
  private Timestamp datetime;
  private BigDecimal open;
  private BigDecimal low;
  private BigDecimal high;
  private BigDecimal close;
  private Currency currency;
  private Long volume;
}
