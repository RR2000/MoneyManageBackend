package com.rondinella.moneymanageapi.stocks;

import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.common.marketdata.Interval;
import com.rondinella.moneymanageapi.common.marketdata.MarketDataConnection;
import yahoofinance.YahooMarketDataConnector;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LocalMarketData implements MarketDataConnection {
  StockRepository stockRepository;

  public LocalMarketData(StockRepository stockRepository) {
    this.stockRepository = stockRepository;
  }

  @Override
  public ParentStock get(String isin) throws IOException {
    List<Stock> stocks = stockRepository.findAllByIsin(isin);
    ParentStock parentStock;
    if (stocks != null && !stocks.isEmpty()) {
      parentStock = new ParentStock(stocks);
    } else {
      parentStock = downloadData(new YahooMarketDataConnector(), isin);
    }
    return parentStock;
  }

  @Override
  public ParentStock get(String isin, boolean includeHistorical) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String isin, Interval interval) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String isin, Calendar from) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String isin, Calendar from, Interval interval) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String isin, Calendar from, Calendar to) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String isin, Calendar from, Calendar to, Interval interval) throws IOException {
    Timestamp fromT = Utils.calendarToTimestamp(from);
    Timestamp toT = Utils.calendarToTimestamp(to);
    List<Stock> stocks = stockRepository.findAllByIsinAndDatetimeBetween(isin, fromT, toT);
    ParentStock parentStock;
    if (stocks != null && !stocks.isEmpty()) {
      parentStock = new ParentStock(stocks);
    } else {
      parentStock = downloadData(new YahooMarketDataConnector(), isin);
    }
    return parentStock;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols) throws IOException {
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, boolean includeHistorical) throws IOException {
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Interval interval) throws IOException {
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from) throws IOException {
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from, Interval interval) throws IOException {
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from, Calendar to) throws IOException {
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from, Calendar to, Interval interval) throws IOException {
    return null;
  }

  @Override
  public String luckySearchTicker(String query) {
    return null;
  }

  public ParentStock downloadData(MarketDataConnection marketDataConnection, String isin) {
    String ticker = marketDataConnection.luckySearchTicker(isin);
    Calendar now = Calendar.getInstance(Locale.ITALY);
    Calendar from = Calendar.getInstance(Locale.ITALY);
    from.set(1900, Calendar.JANUARY, 1);
    ParentStock parentStock;
    try {
      parentStock = marketDataConnection.get(ticker, from, now, Interval.WEEKLY);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    isin = isin.toUpperCase(Locale.ROOT);
    for (Stock s : parentStock.getHistory()) {
      s.setCurrency(parentStock.getCurrency());
      s.setIsin(isin);
    }

    stockRepository.saveAllAndFlush(parentStock.getHistory());

    return parentStock;
  }
}
