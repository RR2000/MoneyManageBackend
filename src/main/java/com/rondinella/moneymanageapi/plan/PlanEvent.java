package com.rondinella.moneymanageapi.plan;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An event of a version (table plan_event): money in or out once, or a change of the monthly payment. */
@Getter
@Setter
@Entity
public class PlanEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  Long versionId;
  int position;
  @Enumerated(EnumType.STRING)
  Projection.Kind kind;
  LocalDate onDate;
  BigDecimal amount;
  String label;
}
