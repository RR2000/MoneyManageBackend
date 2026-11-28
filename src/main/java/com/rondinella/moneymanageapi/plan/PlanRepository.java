package com.rondinella.moneymanageapi.plan;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

@Hidden
public interface PlanRepository extends JpaRepository<Plan, Long> {
}
