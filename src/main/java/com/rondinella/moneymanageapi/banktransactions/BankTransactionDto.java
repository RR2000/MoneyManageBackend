package com.rondinella.moneymanageapi.banktransactions;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * DTO for {@link BankTransaction}
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BankTransactionDto implements Serializable {
  private String account = null;
  private Timestamp datetime = null;
  private String description = null;
  private BigDecimal amount = null;
  private BigDecimal fee = null;
  private String currency = null;
  private BigDecimal cumulativeAmount = null;
}
