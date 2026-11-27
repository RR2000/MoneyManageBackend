package com.rondinella.moneymanageapi.banking;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@Hidden
public interface BankLinkRepository extends JpaRepository<BankLink, Long> {

  List<BankLink> findAllByOrderByIdAsc();

  List<BankLink> findByAspspNameAndAspspCountry(String aspspName, String aspspCountry);
}
