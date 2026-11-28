package com.rondinella.moneymanageapi.plan;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The real value of the investments at the end of {@code month} (its first day), for a plan (table plan_snapshot). */
@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@IdClass(PlanSnapshot.Key.class)
public class PlanSnapshot {
  @Id
  Long planId;
  @Id
  LocalDate month;
  BigDecimal value;
  BigDecimal invested;
  Instant takenAt;

  @Getter
  @Setter
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Key implements Serializable {
    Long planId;
    LocalDate month;

    @Override
    public boolean equals(Object o) {
      return o instanceof Key k && java.util.Objects.equals(planId, k.planId) && java.util.Objects.equals(month, k.month);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(planId, month);
    }
  }
}
