package com.rondinella.moneymanageapi.repositories;

import com.rondinella.moneymanageapi.enitities.BrokerTransaction;
import com.rondinella.moneymanageapi.enitities.ids.BrokerTransactionId;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

@Hidden
public interface BrokerTransactionRepository extends JpaRepository<BrokerTransaction, BrokerTransactionId> {
}