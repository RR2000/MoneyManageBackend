package com.rondinella.moneymanageapi.categories;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/** A category of movements (rent, bills, groceries...); {@code fixed}: a fixed cost, counted on the fixed costs page. */
@Getter
@Setter
@Entity
public class Category {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  String name;
  boolean fixed;
}
