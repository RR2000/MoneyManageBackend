package com.rondinella.moneymanageapi.stocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Weekly prices of an ISIN from Yahoo Finance, with two public calls: the search (ISIN to symbol) and the chart.
 * Neither needs a cookie or a crumb, but Yahoo answers 429 to a client that does not look like a browser
 * (the default Java User-Agent), so a browser one is sent. Every call gives up after {@link #TIMEOUT}.
 */
@Component
public class YahooPrices {

  public static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final String USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";

  /** What Yahoo knows of an ISIN: its symbol, the currency of its prices, one price per week. */
  public record Prices(String symbol, Currency currency, List<Stock> weekly) {
  }

  private final String searchBase;
  private final String chartBase;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(TIMEOUT)
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build();
  private final ObjectMapper json = new ObjectMapper();

  @Autowired
  public YahooPrices(@Value("${app.yahoo.search-url:https://query1.finance.yahoo.com}") String searchBase,
                     @Value("${app.yahoo.chart-url:https://query2.finance.yahoo.com}") String chartBase) {
    this.searchBase = searchBase;
    this.chartBase = chartBase;
  }

  public Prices weekly(String isin) throws IOException {
    String symbol = symbolOf(isin);
    JsonNode result = get(chartBase + "/v8/finance/chart/" + encode(symbol) + "?period1=0&period2="
        + Instant.now().getEpochSecond() + "&interval=1wk").path("chart").path("result").path(0);
    JsonNode times = result.path("timestamp");
    JsonNode quote = result.path("indicators").path("quote").path(0);
    if (!times.isArray() || quote.isMissingNode()) {
      throw new IOException("Yahoo Finance: no prices for the symbol");
    }
    Currency currency = currency(result.path("meta").path("currency").asText(""));
    List<Stock> weekly = new ArrayList<>();
    for (int i = 0; i < times.size(); i++) {
      BigDecimal close = number(quote.path("close").path(i));
      // weeks without trading come back as null: no price, rather than a price of 0
      if (close == null) {
        continue;
      }
      Timestamp at = Timestamp.from(Instant.ofEpochSecond(times.get(i).asLong()));
      JsonNode volume = quote.path("volume").path(i);
      weekly.add(new Stock(symbol, isin, at, number(quote.path("open").path(i)), number(quote.path("low").path(i)),
          number(quote.path("high").path(i)), close, currency, volume.isNumber() ? volume.asLong() : null));
    }
    return new Prices(symbol, currency, weekly);
  }

  String symbolOf(String isin) throws IOException {
    JsonNode symbol = get(searchBase + "/v1/finance/search?q=" + encode(isin) + "&quotesCount=1&newsCount=0")
        .path("quotes").path(0).path("symbol");
    if (!symbol.isTextual() || symbol.asText().isBlank()) {
      throw new IOException("Yahoo Finance: no symbol for the ISIN");
    }
    return symbol.asText();
  }

  private JsonNode get(String url) throws IOException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .timeout(TIMEOUT)
        .GET()
        .build();
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IOException("Yahoo Finance: HTTP " + response.statusCode());
      }
      return json.readTree(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while asking Yahoo Finance", e);
    }
  }

  private static BigDecimal number(JsonNode node) {
    return node.isNumber() ? node.decimalValue() : null;
  }

  private static Currency currency(String code) {
    try {
      // GBp is pence, not pounds: better no currency than a wrong one
      return code.isBlank() || !code.equals(code.toUpperCase(Locale.ROOT)) ? null : Currency.getInstance(code);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
