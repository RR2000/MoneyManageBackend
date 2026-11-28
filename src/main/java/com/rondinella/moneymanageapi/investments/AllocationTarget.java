package com.rondinella.moneymanageapi.investments;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** The share of the portfolio wanted in one ISIN, in percent (table allocation_target, in the user's schema). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class AllocationTarget {
  @Id
  private String isin;
  private BigDecimal percent;
}
