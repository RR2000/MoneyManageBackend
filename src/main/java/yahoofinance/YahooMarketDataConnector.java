package yahoofinance;

import com.rondinella.moneymanageapi.common.marketdata.Interval;
import com.rondinella.moneymanageapi.common.marketdata.MarketDataConnection;
import com.rondinella.moneymanageapi.stocks.ParentStock;

import java.io.IOException;
import java.util.Calendar;
import java.util.Map;

public class YahooMarketDataConnector implements MarketDataConnection {
  YahooMarketDataMapper mapper = YahooMarketDataMapper.INSTANCE;

  @Override
  public ParentStock get(String symbol) throws IOException {
    return mapper.toParentStock(YahooFinance.get(symbol));
  }

  @Override
  public ParentStock get(String symbol, boolean includeHistorical) throws IOException {
    return mapper.toParentStock(YahooFinance.get(symbol, includeHistorical));
  }

  @Override
  public ParentStock get(String symbol, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toParentStock(YahooFinance.get(symbol, yahooInterval));
  }

  @Override
  public ParentStock get(String symbol, Calendar from) throws IOException {
    return mapper.toParentStock(YahooFinance.get(symbol, from));
  }

  @Override
  public ParentStock get(String symbol, Calendar from, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toParentStock(YahooFinance.get(symbol, from, yahooInterval));
  }

  @Override
  public ParentStock get(String symbol, Calendar from, Calendar to) throws IOException {
    return mapper.toParentStock(YahooFinance.get(symbol, from, to));
  }

  @Override
  public ParentStock get(String symbol, Calendar from, Calendar to, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toParentStock(YahooFinance.get(symbol, from, to, yahooInterval));
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols) throws IOException {
    return mapper.toMap(YahooFinance.get(symbols));
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, boolean includeHistorical) throws IOException {
    return mapper.toMap(YahooFinance.get(symbols, includeHistorical));
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toMap(YahooFinance.get(symbols, yahooInterval));
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from) throws IOException {
    return mapper.toMap(YahooFinance.get(symbols, from));
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toMap(YahooFinance.get(symbols, from, yahooInterval));
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from, Calendar to) throws IOException {
    return mapper.toMap(YahooFinance.get(symbols, from, to));
  }

  @Override
  public Map<String, ParentStock> get(String[] symbols, Calendar from, Calendar to, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toMap(YahooFinance.get(symbols, from, to, yahooInterval));
  }

  @Override
  public String luckySearchTicker(String query) {
    return YahooFinance.luckySearchTicker(query);
  }
}
