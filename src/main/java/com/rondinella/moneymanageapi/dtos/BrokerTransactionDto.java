package com.rondinella.moneymanageapi.dtos;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * DTO for {@link com.rondinella.moneymanageapi.enitities.BrokerTransaction}
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BrokerTransactionDto implements Serializable {
  Timestamp datetime;
  String description;
  String isin;
  BigDecimal quantity;
  BigDecimal stockPrice;
  BigDecimal fee;
}