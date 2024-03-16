package com.rondinella.moneymanageapi.dtos;

import com.rondinella.moneymanageapi.enitities.Transaction;
import io.swagger.annotations.ApiModel;
import jakarta.persistence.Id;
import lombok.Value;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * DTO for {@link Transaction}
 */
@ApiModel(description = "DTO for representing a transaction")
@Value
public class TransactionDto implements Serializable {
  @Id
  String account;
  @Id
  Timestamp startedDate;
  @Id
  Timestamp completedDate;
  String description;
  BigDecimal amount;
  BigDecimal fee;
  String currency;
}
