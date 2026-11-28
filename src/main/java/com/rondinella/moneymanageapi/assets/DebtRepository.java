package com.rondinella.moneymanageapi.assets;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

@Hidden
public interface DebtRepository extends JpaRepository<Debt, Long> {
}
