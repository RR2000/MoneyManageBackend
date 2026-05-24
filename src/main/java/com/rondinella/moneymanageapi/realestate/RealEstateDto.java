package com.rondinella.moneymanageapi.realestate;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class RealEstateDto {
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
