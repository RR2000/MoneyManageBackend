package com.rondinella.moneymanageapi.dtos;

import com.rondinella.moneymanageapi.enitities.Transaction;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
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

  @ApiModelProperty(value = "Timestamp of the transaction", example = "2022-03-17T10:15:30Z")
  Timestamp timestamp;

  @ApiModelProperty(value = "Value of the transaction", example = "100.0")
  BigDecimal value;
}
