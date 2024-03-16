package com.rondinella.moneymanageapi.repositories;

import com.rondinella.moneymanageapi.enitities.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.sql.Timestamp;

public interface TransactionRepository extends JpaRepository<Transaction, Timestamp> {

}