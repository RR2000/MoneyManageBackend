package com.rondinella.moneymanageapi.brokertransactions;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * DTO for {@link BrokerTransaction}
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