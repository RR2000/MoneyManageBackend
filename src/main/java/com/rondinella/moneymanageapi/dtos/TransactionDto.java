package com.rondinella.moneymanageapi.dtos;

import com.rondinella.moneymanageapi.enitities.Transaction;
import io.swagger.annotations.ApiModel;
import jakarta.persistence.Id;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * DTO for {@link Transaction}
 */
@ApiModel(description = "DTO for representing a transaction")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TransactionDto implements Serializable {
  String account = null;
  Timestamp startedDate = null;
  Timestamp completedDate = null;
  String description = null;
  BigDecimal amount = null;
  BigDecimal fee = null;
  String currency = null;
}
