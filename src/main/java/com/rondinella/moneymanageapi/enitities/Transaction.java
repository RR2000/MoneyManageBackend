package com.rondinella.moneymanageapi.enitities;

import com.rondinella.moneymanageapi.enitities.ids.TransactionId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Getter
@Setter
@Entity
@Table(name = "transaction")
@IdClass(TransactionId.class)
public class Transaction implements Persistable<TransactionId> {
  @Id
  String account;
  @Id
  Timestamp startedDate;
  @Id
  Timestamp completedDate;
  @Id
  String description;
  BigDecimal amount;
  BigDecimal fee;
  String currency;
  BigDecimal cumulativeAmount;

  @Override
  public TransactionId getId() {
    return new TransactionId(account, startedDate, completedDate, description);
  }

  @Override
  public boolean isNew() {
    return false;
  }
}