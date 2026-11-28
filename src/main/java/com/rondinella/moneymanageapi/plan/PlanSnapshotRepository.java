package com.rondinella.moneymanageapi.plan;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@Hidden
public interface PlanSnapshotRepository extends JpaRepository<PlanSnapshot, PlanSnapshot.Key> {
  /** oldest first */
  List<PlanSnapshot> findByPlanIdOrderByMonthAsc(Long planId);
}
