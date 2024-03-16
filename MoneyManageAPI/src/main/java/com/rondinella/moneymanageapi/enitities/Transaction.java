package com.rondinella.moneymanageapi.enitities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Getter
@Setter
@Entity
@Table(name = "transaction")
public class Transaction {
  @Id
  Timestamp timestamp;
  BigDecimal value;
}