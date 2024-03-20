package com.rondinella.moneymanageapi.dtos;

import com.rondinella.moneymanageapi.enitities.Transaction;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * DTO for {@link Transaction}
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TransactionDto implements Serializable {
  String account = null;
  Timestamp datetime = null;
  String description = null;
  BigDecimal amount = null;
  BigDecimal fee = null;
  String currency = null;
  BigDecimal cumulativeAmount = null;
}
