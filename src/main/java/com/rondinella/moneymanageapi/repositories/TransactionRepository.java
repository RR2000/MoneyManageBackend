package com.rondinella.moneymanageapi.repositories;

import com.rondinella.moneymanageapi.enitities.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.sql.Timestamp;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Timestamp> {
  @Query("SELECT DISTINCT t.account FROM Transaction t")
  List<String> findDistinctAccounts();

  List<Transaction> findTransactionByAccountOrderByCompletedDate(String account);

  List<Transaction> findTransactionByAccountOrderByCompletedDateDesc(String account);

  List<Transaction> findTransactionByAccountAndCompletedDateGreaterThanOrderByCompletedDateDesc(String account, Timestamp day);

  List<Transaction> findByCompletedDateBetween(Timestamp startTimestamp, Timestamp endTimestamp);
}