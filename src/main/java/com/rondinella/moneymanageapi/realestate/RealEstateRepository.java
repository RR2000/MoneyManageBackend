package com.rondinella.moneymanageapi.realestate;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

@Hidden
public interface RealEstateRepository extends JpaRepository<RealEstate, Long> {
}
