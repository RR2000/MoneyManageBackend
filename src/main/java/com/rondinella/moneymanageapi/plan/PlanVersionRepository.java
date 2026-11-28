package com.rondinella.moneymanageapi.plan;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@Hidden
public interface PlanVersionRepository extends JpaRepository<PlanVersion, Long> {
  /** newest first */
  List<PlanVersion> findByPlanIdOrderByNumberDesc(Long planId);

  Optional<PlanVersion> findFirstByPlanIdOrderByNumberDesc(Long planId);

  Optional<PlanVersion> findByPlanIdAndNumber(Long planId, int number);

  long countByPlanId(Long planId);
}
