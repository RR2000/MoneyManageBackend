package yahoofinance;

import com.rondinella.moneymanageapi.common.marketdata.Interval;
import com.rondinella.moneymanageapi.common.marketdata.MarketDataConnection;
import com.rondinella.moneymanageapi.common.marketdata.Stock;

import java.io.IOException;
import java.util.Calendar;
import java.util.Map;

public class YahooMarketDataConnector implements MarketDataConnection {
  YahooMarketDataMapper mapper = YahooMarketDataMapper.INSTANCE;

  @Override
  public Stock get(String symbol) throws IOException {
    return mapper.toStock(YahooFinance.get(symbol));
  }

  @Override
  public Stock get(String symbol, boolean includeHistorical) throws IOException {
    return mapper.toStock(YahooFinance.get(symbol, includeHistorical));
  }

  @Override
  public Stock get(String symbol, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toStock(YahooFinance.get(symbol, yahooInterval));
  }

  @Override
  public Stock get(String symbol, Calendar from) throws IOException {
    return mapper.toStock(YahooFinance.get(symbol, from));
  }

  @Override
  public Stock get(String symbol, Calendar from, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toStock(YahooFinance.get(symbol, from, yahooInterval));
  }

  @Override
  public Stock get(String symbol, Calendar from, Calendar to) throws IOException {
    return mapper.toStock(YahooFinance.get(symbol, from, to));
  }

  @Override
  public Stock get(String symbol, Calendar from, Calendar to, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toStock(YahooFinance.get(symbol, from, to, yahooInterval));
  }

  @Override
  public Map<String, Stock> get(String[] symbols) throws IOException {
    return mapper.toMap(YahooFinance.get(symbols));
  }

  @Override
  public Map<String, Stock> get(String[] symbols, boolean includeHistorical) throws IOException {
    return mapper.toMap(YahooFinance.get(symbols, includeHistorical));
  }

  @Override
  public Map<String, Stock> get(String[] symbols, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toMap(YahooFinance.get(symbols, yahooInterval));
  }

  @Override
  public Map<String, Stock> get(String[] symbols, Calendar from) throws IOException {
    return mapper.toMap(YahooFinance.get(symbols, from));
  }

  @Override
  public Map<String, Stock> get(String[] symbols, Calendar from, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toMap(YahooFinance.get(symbols, from, yahooInterval));
  }

  @Override
  public Map<String, Stock> get(String[] symbols, Calendar from, Calendar to) throws IOException {
    return mapper.toMap(YahooFinance.get(symbols, from, to));
  }

  @Override
  public Map<String, Stock> get(String[] symbols, Calendar from, Calendar to, Interval interval) throws IOException {
    yahoofinance.histquotes.Interval yahooInterval = mapper.toYahoo(interval);
    return mapper.toMap(YahooFinance.get(symbols, from, to, yahooInterval));
  }

  @Override
  public String luckySearchTicker(String query) {
    return YahooFinance.luckySearchTicker(query);
  }
}
