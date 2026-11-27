package com.rondinella.moneymanageapi.banktransactions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * One movement of a bank account. Every movement has an id of its own: two movements of the same day with the same
 * description are two rows. {@link #externalRef} is the bank's id of the movement (open banking), unique per account.
 */
@Getter
@Setter
@Entity
public class BankTransaction {

  /** Where a movement comes from. */
  public enum Source {
    FILE,
    MANUAL,
    BANK
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  String account;
  Timestamp datetime;
  String description;
  BigDecimal amount;
  BigDecimal fee;
  String currency;
  BigDecimal cumulativeAmount;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  Source source = Source.FILE;
  String externalRef;
}
