package yahoofinance;

import com.rondinella.moneymanageapi.common.Utils;
import com.rondinella.moneymanageapi.stocks.ParentStock;
import com.rondinella.moneymanageapi.stocks.Stock;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import yahoofinance.histquotes.HistoricalQuote;
import yahoofinance.histquotes.Interval;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

@Mapper
public interface YahooMarketDataMapper {
  YahooMarketDataMapper INSTANCE = new YahooMarketDataMapperImpl();

  default Timestamp map(Calendar value) {
    return Utils.stringToTimestamp(Utils.calendarToString(value));
  }

  @Mapping(target = "datetime", source = "date")
  @Mapping(target = "close", source = "close")
  Stock toStock(HistoricalQuote quote) throws IOException;

  //todo it has to take the last not the first
  @Mapping(target = "datetime", expression = "java(map(stock.getHistory().get(0).getDate()))")
  @Mapping(target = "close", source = "quote.previousClose")
  ParentStock toParentStock(yahoofinance.Stock stock) throws IOException;

  Interval toYahoo(com.rondinella.moneymanageapi.common.marketdata.Interval interval);

  Map<String, ParentStock> toMap(Map<String, yahoofinance.Stock> stringStockMap);
}
