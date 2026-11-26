package com.rondinella.moneymanageapi.stocks;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public interface StockRepository extends JpaRepository<Stock, StockId> {
  ArrayList<Stock> findAllByIsin(String isin);
  ArrayList<Stock> findAllByIsinAndDatetimeBetween(String isin, Timestamp from, Timestamp to);

  List<Stock> findAllByIsinAndDatetimeBetweenOrderByDatetime(String isin, Timestamp from, Timestamp to);

  /** The date of the newest price of the ISIN, null if it has none. */
  @Query("SELECT MAX(s.datetime) FROM Stock s WHERE s.isin = :isin")
  Timestamp latestDatetime(@Param("isin") String isin);
}