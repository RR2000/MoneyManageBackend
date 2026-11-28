package com.rondinella.moneymanageapi.investments;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AllocationTargetRepository extends JpaRepository<AllocationTarget, String> {
  List<AllocationTarget> findAllByOrderByIsinAsc();
}
