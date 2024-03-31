package com.rondinella.moneymanageapi.common.marketdata;


import java.io.IOException;
import java.util.Calendar;
import java.util.Map;

public interface MarketDataConnection {
  Stock get(String symbol) throws IOException;

  Stock get(String symbol, boolean includeHistorical) throws IOException;

  Stock get(String symbol, Interval interval) throws IOException;

  Stock get(String symbol, Calendar from) throws IOException;

  Stock get(String symbol, Calendar from, Interval interval) throws IOException;

  Stock get(String symbol, Calendar from, Calendar to) throws IOException;

  Stock get(String symbol, Calendar from, Calendar to, Interval interval) throws IOException;

  Map<String, Stock> get(String[] symbols) throws IOException;

  Map<String, Stock> get(String[] symbols, boolean includeHistorical) throws IOException;

  Map<String, Stock> get(String[] symbols, Interval interval) throws IOException;

  Map<String, Stock> get(String[] symbols, Calendar from) throws IOException;

  Map<String, Stock> get(String[] symbols, Calendar from, Interval interval) throws IOException;

  Map<String, Stock> get(String[] symbols, Calendar from, Calendar to) throws IOException;

  Map<String, Stock> get(String[] symbols, Calendar from, Calendar to, Interval interval) throws IOException;

  String luckySearchTicker(String query);
}

