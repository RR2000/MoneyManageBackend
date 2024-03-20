package com.rondinella.moneymanageapi.repositories;

import com.rondinella.moneymanageapi.enitities.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.sql.Timestamp;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Timestamp> {
  @Query("SELECT DISTINCT t.account FROM Transaction t")
  List<String> findDistinctAccounts();

  List<Transaction> findTransactionByAccountOrderByDatetime(String account);

  List<Transaction> findTransactionByAccountOrderByDatetimeDesc(String account);

  List<Transaction> findTransactionByAccountAndDatetimeGreaterThanOrderByDatetimeDesc(String account, Timestamp day);

  List<Transaction> findByDatetimeBetweenAndAccount(Timestamp startTimestamp, Timestamp endTimestamp, String account);

  List<Transaction> findByDatetimeBetweenAndAccountOrderByDatetime(Timestamp startTimestamp, Timestamp endTimestamp, String account);
}