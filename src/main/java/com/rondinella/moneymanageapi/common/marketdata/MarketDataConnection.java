package com.rondinella.moneymanageapi.common.marketdata;


import com.rondinella.moneymanageapi.stocks.ParentStock;

import java.io.IOException;
import java.util.Calendar;
import java.util.Map;

public interface MarketDataConnection {
  ParentStock get(String symbol) throws IOException;

  ParentStock get(String symbol, boolean includeHistorical) throws IOException;

  ParentStock get(String symbol, Interval interval) throws IOException;

  ParentStock get(String symbol, Calendar from) throws IOException;

  ParentStock get(String symbol, Calendar from, Interval interval) throws IOException;

  ParentStock get(String symbol, Calendar from, Calendar to) throws IOException;

  ParentStock get(String symbol, Calendar from, Calendar to, Interval interval) throws IOException;

  Map<String, ParentStock> get(String[] symbols) throws IOException;

  Map<String, ParentStock> get(String[] symbols, boolean includeHistorical) throws IOException;

  Map<String, ParentStock> get(String[] symbols, Interval interval) throws IOException;

  Map<String, ParentStock> get(String[] symbols, Calendar from) throws IOException;

  Map<String, ParentStock> get(String[] symbols, Calendar from, Interval interval) throws IOException;

  Map<String, ParentStock> get(String[] symbols, Calendar from, Calendar to) throws IOException;

  Map<String, ParentStock> get(String[] symbols, Calendar from, Calendar to, Interval interval) throws IOException;

  String luckySearchTicker(String query);
}

