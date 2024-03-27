package com.rondinella.moneymanageapi.repositories;

import com.rondinella.moneymanageapi.enitities.BrokerTransaction;
import com.rondinella.moneymanageapi.enitities.ids.BrokerTransactionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

public interface BrokerTransactionRepository extends JpaRepository<BrokerTransaction, BrokerTransactionId> {
  @Query("SELECT DISTINCT t.isin FROM BrokerTransaction t")
  List<String> findDistinctIsin();

  @Query("SELECT SUM(t.quantity) FROM BrokerTransaction t WHERE t.isin = :isin")
  BigDecimal totalQuantityByIsin(@Param("isin") String isin);

  @Query("SELECT SUM(t.quantity) FROM BrokerTransaction t WHERE t.isin = :isin AND t.datetime <= :datetime")
  BigDecimal totalQuantityByIsinGreaterThan(@Param("isin") String isin, @Param("datetime")Timestamp datetime);
}