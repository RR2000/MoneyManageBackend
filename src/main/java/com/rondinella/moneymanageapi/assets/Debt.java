package com.rondinella.moneymanageapi.assets;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** What the user owes (a mortgage, a car loan): what is left today, and optionally rate, payment and end (table debt). */
@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
public class Debt {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String name;
  private BigDecimal balance;
  private String currency;
  /** yearly, in percent */
  private BigDecimal annualRate;
  private BigDecimal monthlyPayment;
  private LocalDate endDate;
}
