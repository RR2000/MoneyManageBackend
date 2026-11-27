package com.rondinella.moneymanageapi.banking;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An account of a {@link BankLink}. Its movements go to the money account {@link #moneyAccount} (an account name of
 * bank_transaction), none until the user picks one. Only the masked IBAN is kept.
 */
@Getter
@Setter
@Entity
public class BankLinkAccount {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  Long linkId;
  /** Enable Banking's id of the account, valid only for the session. Never returned. */
  String uid;
  /** Same account across sessions: a new consent finds it (and its money account) again. */
  String identificationHash;
  String name;
  String ibanMasked;
  String currency;
  String moneyAccount;
  /** The first day imported: the day after the last movement of the money account when it was picked. */
  LocalDate syncFrom;
  LocalDate lastBookingDate;
  BigDecimal balanceAmount;
  String balanceCurrency;
  String balanceType;
  LocalDate balanceDate;
}
