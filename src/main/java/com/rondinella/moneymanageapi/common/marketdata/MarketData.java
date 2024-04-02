package com.rondinella.moneymanageapi.common.marketdata;

import com.rondinella.moneymanageapi.stocks.ParentStock;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Map;

@Service
public class MarketData implements MarketDataConnection {

  ArrayList<MarketDataConnection> sources = new ArrayList<>();

  public MarketData() {

  }

  public MarketData(ArrayList<MarketDataConnection> sources) {
    this.sources = sources;
  }

  public void addSource(MarketDataConnection marketDataConnection) {
    if (sources == null)
      sources = new ArrayList<>();

    sources.add(marketDataConnection);
  }

  @Override
  public ParentStock get(String symbol) throws IOException {
    for (MarketDataConnection source : sources) {
      ParentStock result = source.get(symbol);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public ParentStock get(String symbol, boolean includeHistorical) throws IOException {
    for (MarketDataConnection source : sources) {
      ParentStock result = source.get(symbol, includeHistorical);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public ParentStock get(String symbol, Interval interval) throws IOException {
    for (MarketDataConnection source : sources) {
      ParentStock result = source.get(symbol, interval);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public ParentStock get(String symbol, Calendar from) throws IOException {
    for (MarketDataConnection source : sources) {
      ParentStock result = source.get(symbol, from);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public ParentStock get(String symbol, Calendar from, Interval interval) throws IOException {
    for (MarketDataConnection source : sources) {
      ParentStock result = source.get(symbol, from, interval);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public ParentStock get(String symbol, Calendar from, Calendar to) throws IOException {
    for (MarketDataConnection source : sources) {
      ParentStock result = source.get(symbol, from, to);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public ParentStock get(String symbol, Calendar from, Calendar to, Interval interval) throws IOException {
    for (MarketDataConnection source : sources) {
      ParentStock result = source.get(symbol, from, to, interval);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols) throws IOException {
    for (MarketDataConnection source : sources) {
      Map<String, ParentStock> result = source.get(symbols);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, boolean includeHistorical) throws IOException {
    for (MarketDataConnection source : sources) {
      Map<String, ParentStock> result = source.get(symbols, includeHistorical);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Interval interval) throws IOException {
    for (MarketDataConnection source : sources) {
      Map<String, ParentStock> result = source.get(symbols, interval);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from) throws IOException {
    for (MarketDataConnection source : sources) {
      Map<String, ParentStock> result = source.get(symbols, from);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from, Interval interval) throws IOException {
    for (MarketDataConnection source : sources) {
      Map<String, ParentStock> result = source.get(symbols, from, interval);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from, Calendar to) throws IOException {
    for (MarketDataConnection source : sources) {
      Map<String, ParentStock> result = source.get(symbols, from, to);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from, Calendar to, Interval interval) throws IOException {
    for (MarketDataConnection source : sources) {
      Map<String, ParentStock> result = source.get(symbols, from, to, interval);
      if (result != null)
        return result;
    }
    return null;
  }

  @Override
  public String luckySearchTicker(String query) {
    for (MarketDataConnection source : sources) {
      String result = source.luckySearchTicker(query);
      if (result != null)
        return result;
    }
    return null;
  }
}
