package com.rondinella.moneymanageapi.categories;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/**
 * A movement whose description contains {@code pattern} (any case) gets the category; {@code direction} IN or OUT
 * only money in or out (null: both), {@code account} only that money account (null: every account).
 */
@Getter
@Setter
@Entity
public class CategoryRule {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  Long categoryId;
  String pattern;
  String direction;
  String account;
}
