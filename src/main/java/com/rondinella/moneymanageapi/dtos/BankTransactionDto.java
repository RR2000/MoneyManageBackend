package com.rondinella.moneymanageapi.dtos;

import com.rondinella.moneymanageapi.enitities.BankTransaction;
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
  String account = null;
  Timestamp datetime = null;
  String description = null;
  BigDecimal amount = null;
  BigDecimal fee = null;
  String currency = null;
  BigDecimal cumulativeAmount = null;
}
