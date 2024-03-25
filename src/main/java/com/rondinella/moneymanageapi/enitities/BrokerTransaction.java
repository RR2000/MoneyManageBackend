package com.rondinella.moneymanageapi.enitities;

import com.rondinella.moneymanageapi.enitities.ids.BrokerTransactionId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.sql.Timestamp;
@Getter
@Setter
@Entity
@IdClass(BrokerTransactionId.class)
public class BrokerTransaction {
  @Id
  Timestamp datetime;
  String description;
  @Id
  String isin;
  @Id
  BigDecimal quantity;
  @Id
  BigDecimal stockPrice;
  BigDecimal fee;
}
