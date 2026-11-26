package com.rondinella.moneymanageapi.stocks;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IsinListingRepository extends JpaRepository<IsinListing, String> {
}
