package com.rondinella.moneymanageapi.stocks;

import com.rondinella.moneymanageapi.common.marketdata.Interval;
import com.rondinella.moneymanageapi.common.marketdata.MarketDataConnection;

import java.io.IOException;
import java.util.Calendar;
import java.util.Map;

public class LocalMarketData implements MarketDataConnection {
  StockRepository stockRepository;

  public LocalMarketData(StockRepository stockRepository) {
    this.stockRepository = stockRepository;
  }

  @Override
  public ParentStock get(String symbol) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String symbol, boolean includeHistorical) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String symbol, Interval interval) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String symbol, Calendar from) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String symbol, Calendar from, Interval interval) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String symbol, Calendar from, Calendar to) throws IOException {
    return null;
  }

  @Override
  public ParentStock get(String symbol, Calendar from, Calendar to, Interval interval) throws IOException {
    return null;
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
}
