package com.rondinella.moneymanageapi.realestate;

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

@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
public class RealEstate {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  String name;
  String address;
  LocalDate purchaseDate;
  BigDecimal purchasePrice;
  BigDecimal currentEstimatedValue;
  BigDecimal monthlyRent;
  BigDecimal monthlyExpenses;
  String currency;
}
