package com.rondinella.moneymanageapi.enitities;

import com.rondinella.moneymanageapi.enitities.ids.BankTransactionId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.sql.Timestamp;

@Getter
@Setter
@Entity
@IdClass(BankTransactionId.class)
public class BankTransaction implements Persistable<BankTransactionId> {
  @Id
  String account;
  @Id
  Timestamp datetime;
  @Id
  String description;
  BigDecimal amount;
  BigDecimal fee;
  String currency;
  BigDecimal cumulativeAmount;

  @Override
  public BankTransactionId getId() {
    return new BankTransactionId(account, datetime, description);
  }

  @Override
  public boolean isNew() {
    return false;
  }
}