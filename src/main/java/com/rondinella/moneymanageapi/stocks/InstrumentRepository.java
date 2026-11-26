package com.rondinella.moneymanageapi.stocks;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<Instrument, String> {
}
