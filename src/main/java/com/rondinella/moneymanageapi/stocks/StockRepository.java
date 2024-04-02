package com.rondinella.moneymanageapi.stocks;

import org.springframework.data.jpa.repository.JpaRepository;

import java.sql.Timestamp;
import java.util.ArrayList;

public interface StockRepository extends JpaRepository<Stock, StockId> {
  ArrayList<Stock> findAllByIsin(String isin);
  ArrayList<Stock> findAllByIsinAndDatetimeBetween(String isin, Timestamp from, Timestamp to);
}