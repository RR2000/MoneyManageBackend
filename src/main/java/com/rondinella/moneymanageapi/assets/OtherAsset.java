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

/** Something the user owns that is not an account, an investment or real estate (table other_asset). */
@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
public class OtherAsset {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  private String name;
  /** see {@link Assets#KINDS} */
  private String kind;
  private BigDecimal estimatedValue;
  private String currency;
  /** the day of the estimate; null: unknown, counted on every day */
  private LocalDate valuedOn;
}
