package com.rondinella.moneymanageapi.banktransactions;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
@IdClass(DailyBalanceId.class)
public class DailyBalance {
  @Id
  String account;
  @Id
  LocalDate date;
  BigDecimal balance;
}
