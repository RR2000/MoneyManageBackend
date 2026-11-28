package com.rondinella.moneymanageapi.plan;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@Hidden
public interface PlanEventRepository extends JpaRepository<PlanEvent, Long> {
  List<PlanEvent> findByVersionIdOrderByPositionAsc(Long versionId);
}
