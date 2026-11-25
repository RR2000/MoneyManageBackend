package com.rondinella.moneymanageapi.banktransactions;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Hidden
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Timestamp> {
  @Query("SELECT DISTINCT t.account FROM BankTransaction t")
  List<String> findDistinctAccounts();

  List<BankTransaction> findTransactionByAccountOrderByDatetime(String account);

  List<BankTransaction> findTransactionByAccountOrderByDatetimeDesc(String account);

  List<BankTransaction> findTransactionByAccountAndDatetimeGreaterThanOrderByDatetimeDesc(String account, Timestamp day);

  List<BankTransaction> findByDatetimeBetweenAndAccount(Timestamp startTimestamp, Timestamp endTimestamp, String account);

  List<BankTransaction> findByDatetimeBetweenAndAccountOrderByDatetime(Timestamp startTimestamp, Timestamp endTimestamp, String account);

  List<BankTransaction> findByDatetimeLessThanEqualAndAccountAndAmountGreaterThanOrderByDatetime(
      Timestamp endTimestamp,
      String account,
      BigDecimal amount);

  @Query("""
      SELECT DATE(bt.datetime) AS day, SUM(bt.amount)
      FROM BankTransaction bt
      WHERE bt.account = :account
      AND bt.amount > 0
      AND bt.datetime BETWEEN :startTimestamp AND :endTimestamp
      GROUP BY DATE(bt.datetime)
      ORDER BY DATE(bt.datetime)""")
  List<Object[]> findDailyDepositSumByAccountAndDateRange(String account, Timestamp startTimestamp, Timestamp endTimestamp);

  @Query("""
      SELECT bt FROM BankTransaction bt
      WHERE bt.datetime < :startTimestamp
      AND bt.account = :account
      ORDER BY bt.datetime DESC
      FETCH FIRST 1 ROWS ONLY""")
  BankTransaction findFirstBeforeStartTimestamp(
      Timestamp startTimestamp, String account);

  @Query("""
      SELECT bt FROM BankTransaction bt
      WHERE bt.datetime > :endTimestamp
      AND bt.account = :account
      ORDER BY bt.datetime ASC
      FETCH FIRST 1 ROWS ONLY""")
  BankTransaction findFirstAfterEndTimestamp(
      Timestamp endTimestamp, String account);
}