package com.rondinella.moneymanageapi.repositories;

import com.rondinella.moneymanageapi.enitities.BrokerTransaction;
import com.rondinella.moneymanageapi.enitities.ids.BrokerTransactionId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrokerTransactionRepository extends JpaRepository<BrokerTransaction, BrokerTransactionId> {
}