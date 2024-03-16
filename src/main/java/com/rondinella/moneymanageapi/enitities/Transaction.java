package com.rondinella.moneymanageapi.enitities;

import com.rondinella.moneymanageapi.enitities.ids.TransactionId;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Getter
@Setter
@Entity
@Table(name = "transaction")
@IdClass(TransactionId.class)
public class Transaction {
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