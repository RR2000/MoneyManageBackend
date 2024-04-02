package com.rondinella.moneymanageapi.brokertransactions;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class BrokerTransactionId implements Serializable {
  Timestamp datetime;
  String isin;
  BigDecimal quantity;
  BigDecimal stockPrice;
}
