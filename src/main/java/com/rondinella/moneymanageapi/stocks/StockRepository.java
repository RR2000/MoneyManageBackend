package com.rondinella.moneymanageapi.stocks;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<Stock, StockId> {
  Stock findAllByIsin(String isin);
}