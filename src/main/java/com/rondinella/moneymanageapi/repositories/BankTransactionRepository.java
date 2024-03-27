package com.rondinella.moneymanageapi.repositories;

import com.rondinella.moneymanageapi.enitities.BankTransaction;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.sql.Timestamp;
import java.util.List;

@Hidden
public interface BankTransactionRepository extends JpaRepository<BankTransaction, Timestamp> {
  @Query("SELECT DISTINCT t.account FROM BankTransaction t")
  List<String> findDistinctAccounts();

  List<BankTransaction> findTransactionByAccountOrderByDatetime(String account);

  List<BankTransaction> findTransactionByAccountOrderByDatetimeDesc(String account);

  List<BankTransaction> findTransactionByAccountAndDatetimeGreaterThanOrderByDatetimeDesc(String account, Timestamp day);

  List<BankTransaction> findByDatetimeBetweenAndAccount(Timestamp startTimestamp, Timestamp endTimestamp, String account);

  List<BankTransaction> findByDatetimeBetweenAndAccountOrderByDatetime(Timestamp startTimestamp, Timestamp endTimestamp, String account);
}