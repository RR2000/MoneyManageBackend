package com.rondinella.moneymanageapi.banktransactions;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Hidden
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Long> {
  @Query("SELECT DISTINCT t.account FROM BankTransaction t")
  List<String> findDistinctAccounts();

  // movements of the same moment: by id, the order they were added (the balances are rebuilt in this order)
  List<BankTransaction> findTransactionByAccountOrderByDatetimeAscIdAsc(String account);

  List<BankTransaction> findTransactionByAccountOrderByDatetimeDescIdDesc(String account);

  BankTransaction findFirstByAccountAndDatetimeLessThanEqualOrderByDatetimeDescIdDesc(String account, Timestamp day);

  BankTransaction findFirstByAccountOrderByDatetimeDescIdDesc(String account);

  List<BankTransaction> findByAccountAndDatetimeBetweenOrderByIdAsc(String account, Timestamp from, Timestamp to);

  /** Rows without a bank id in a period: the ones a movement from the bank can be matched to. */
  List<BankTransaction> findByAccountAndExternalRefIsNullAndDatetimeBetweenOrderByIdAsc(String account, Timestamp from,
                                                                                        Timestamp to);

  @Query("SELECT t.externalRef FROM BankTransaction t WHERE t.account = :account AND t.externalRef IS NOT NULL")
  List<String> findExternalRefs(String account);

  List<BankTransaction> findByDatetimeBetweenAndAccount(Timestamp startTimestamp, Timestamp endTimestamp, String account);

  List<BankTransaction> findByDatetimeBetweenAndAccountOrderByDatetimeAscIdAsc(Timestamp startTimestamp, Timestamp endTimestamp, String account);

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
      ORDER BY bt.datetime DESC, bt.id DESC
      FETCH FIRST 1 ROWS ONLY""")
  BankTransaction findFirstBeforeStartTimestamp(
      Timestamp startTimestamp, String account);

  @Query("""
      SELECT bt FROM BankTransaction bt
      WHERE bt.datetime > :endTimestamp
      AND bt.account = :account
      ORDER BY bt.datetime ASC, bt.id ASC
      FETCH FIRST 1 ROWS ONLY""")
  BankTransaction findFirstAfterEndTimestamp(
      Timestamp endTimestamp, String account);
}