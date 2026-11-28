package com.rondinella.moneymanageapi.categories;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@Hidden
public interface CategoryRuleRepository extends JpaRepository<CategoryRule, Long> {
  List<CategoryRule> findByCategoryId(Long categoryId);
}
