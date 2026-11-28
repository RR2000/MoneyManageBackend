package com.rondinella.moneymanageapi.plan;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One saved version of a plan (table plan_version): never changed after saving. {@code *Own}: the user's value
 * (false: money's at saving). Rates in percent a year.
 */
@Getter
@Setter
@Entity
public class PlanVersion {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  Long planId;
  int number;
  Instant savedAt;
  LocalDate savedOn;
  LocalDate targetDate;
  BigDecimal startValue;
  boolean startValueOwn;
  BigDecimal invested;
  boolean investedOwn;
  BigDecimal monthly;
  boolean monthlyOwn;
  BigDecimal moneyValue;
  BigDecimal historicalRate;
  BigDecimal inflation;
  BigDecimal taxRate;
  BigDecimal ratePrudent;
  BigDecimal rateBase;
  BigDecimal rateOptimistic;
}
