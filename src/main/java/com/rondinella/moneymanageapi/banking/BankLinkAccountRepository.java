package com.rondinella.moneymanageapi.banking;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

@Hidden
public interface BankLinkAccountRepository extends JpaRepository<BankLinkAccount, Long> {

  List<BankLinkAccount> findByLinkIdOrderByIdAsc(Long linkId);

  List<BankLinkAccount> findByMoneyAccount(String moneyAccount);

  List<BankLinkAccount> findByIdentificationHash(String identificationHash);
}
