package com.rondinella.moneymanageapi.stocks;

import org.mapstruct.Mapper;

@Mapper
public interface StocksMapper {
  StocksMapper INSTANCE = new StocksMapperImpl(); // Singleton instance

}
