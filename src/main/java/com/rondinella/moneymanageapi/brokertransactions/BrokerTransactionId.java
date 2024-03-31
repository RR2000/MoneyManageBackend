package com.rondinella.moneymanageapi.brokertransactions;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;

public class BrokerTransactionId implements Serializable {
  Timestamp datetime;
  String isin;
  BigDecimal quantity;
  BigDecimal stockPrice;
}
