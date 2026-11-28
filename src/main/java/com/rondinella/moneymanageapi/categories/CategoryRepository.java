package com.rondinella.moneymanageapi.categories;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

@Hidden
public interface CategoryRepository extends JpaRepository<Category, Long> {
}
