package com.rondinella.moneymanageapi.brokertransactions;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * One execution at the broker. Every execution has an id of its own: two fills of the same order in the same minute
 * at the same price are two rows. {@link #orderId} is the broker's order (Degiro "Order ID", several fills can share
 * it); {@link #currency} is the currency of the price, {@link #feeCurrency} the one of the fee, never converted.
 */
@Getter
@Setter
@Entity
public class BrokerTransaction {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  Timestamp datetime;
  String description;
  String isin;
  BigDecimal quantity;
  BigDecimal stockPrice;
  BigDecimal fee;
  String currency;
  String feeCurrency;
  String orderId;
}
