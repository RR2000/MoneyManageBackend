package com.rondinella.moneymanageapi.stocks;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.sql.Timestamp;
import java.util.Currency;

/** Where the prices of one ISIN of the user come from (table instrument, in the user's schema). */
@Getter
@Setter
@NoArgsConstructor
@Entity
public class Instrument {
  @Id
  private String isin;
  /** Alpha Vantage symbol (IWDA.AMS); null: none found */
  private String symbol;
  private Currency currency;
  private String name;
  /** set by hand on the investments page: never replaced by the automatic search */
  private boolean manual;
  private Timestamp resolvedAt;
}
