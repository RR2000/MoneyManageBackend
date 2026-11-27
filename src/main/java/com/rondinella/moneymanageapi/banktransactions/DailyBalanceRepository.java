package com.rondinella.moneymanageapi.banktransactions;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Hidden
public interface DailyBalanceRepository extends JpaRepository<DailyBalance, DailyBalanceId> {
  List<DailyBalance> findByAccountAndDateBetweenOrderByDate(String account, LocalDate from, LocalDate to);

  List<DailyBalance> findByAccountOrderByDate(String account);

  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("DELETE FROM DailyBalance d WHERE d.account = :account")
  int deleteAllOfAccount(String account);
}
