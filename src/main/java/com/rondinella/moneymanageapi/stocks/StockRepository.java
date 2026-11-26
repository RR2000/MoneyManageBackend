package com.rondinella.moneymanageapi.stocks;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public interface StockRepository extends JpaRepository<Stock, StockId> {
  ArrayList<Stock> findAllByIsin(String isin);
  ArrayList<Stock> findAllByIsinAndDatetimeBetween(String isin, Timestamp from, Timestamp to);

  List<Stock> findAllByIsinAndDatetimeBetweenOrderByDatetime(String isin, Timestamp from, Timestamp to);

  List<Stock> findAllByIsinAndSymbolInAndDatetimeBetweenOrderByDatetime(String isin, Collection<String> symbols,
                                                                        Timestamp from, Timestamp to);

  boolean existsByIsinAndSymbol(String isin, String symbol);

  long countByIsin(String isin);

  /** The newest price of the ISIN among these symbols, null if none. */
  Stock findFirstByIsinAndSymbolInOrderByDatetimeDesc(String isin, Collection<String> symbols);

  Stock findFirstByIsinOrderByDatetimeDesc(String isin);

  /** The date of the newest price of the ISIN, null if it has none. */
  @Query("SELECT MAX(s.datetime) FROM Stock s WHERE s.isin = :isin")
  Timestamp latestDatetime(@Param("isin") String isin);

  @Query("SELECT MAX(s.datetime) FROM Stock s WHERE s.isin = :isin AND s.symbol = :symbol")
  Timestamp latestDatetime(@Param("isin") String isin, @Param("symbol") String symbol);

  @Modifying
  @Query("DELETE FROM Stock s WHERE s.isin = :isin AND s.symbol = :symbol AND s.datetime >= :from")
  int deleteFrom(@Param("isin") String isin, @Param("symbol") String symbol, @Param("from") Timestamp from);

  @Modifying
  @Query("DELETE FROM Stock s WHERE s.isin = :isin AND s.symbol = :symbol AND s.datetime BETWEEN :from AND :to")
  int deleteBetween(@Param("isin") String isin, @Param("symbol") String symbol, @Param("from") Timestamp from,
                    @Param("to") Timestamp to);

  /** Prices of the ISIN from other sources than these (after its symbol changed). */
  @Modifying
  @Query("DELETE FROM Stock s WHERE s.isin = :isin AND s.symbol NOT IN :keep")
  int deleteOtherSymbols(@Param("isin") String isin, @Param("keep") Collection<String> keep);
}
