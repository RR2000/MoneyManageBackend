package yahoofinance;

import com.rondinella.moneymanageapi.common.marketdata.Stock;
import lombok.SneakyThrows;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import yahoofinance.histquotes.Interval;

import java.io.IOException;
import java.util.Map;

@Mapper
public interface YahooMarketDataMapper {
  YahooMarketDataMapper INSTANCE = new YahooMarketDataMapperImpl();

  @Mapping(target = "close", source = "quote.previousClose")
  Stock toStock(yahoofinance.Stock stock) throws IOException;

  Interval toYahoo(com.rondinella.moneymanageapi.common.marketdata.Interval interval);

  Map<String, Stock> toMap(Map<String,yahoofinance.Stock> stringStockMap);
}
