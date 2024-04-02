package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.common.marketdata.MarketData;
import com.rondinella.moneymanageapi.stocks.LocalMarketData;
import com.rondinella.moneymanageapi.stocks.StockRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import yahoofinance.YahooMarketDataConnector;

@SpringBootApplication
public class MoneyManageApiApplication {

  final
  MarketData marketData;

  public MoneyManageApiApplication(MarketData marketData, StockRepository stockRepository) {
    //ordered by first chosen
    marketData.addSource(new LocalMarketData(stockRepository));
    marketData.addSource(new YahooMarketDataConnector());
    this.marketData = marketData;
  }

  public static void main(String[] args) {
    SpringApplication.run(MoneyManageApiApplication.class, args);
  }

}
