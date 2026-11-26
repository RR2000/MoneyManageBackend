package com.rondinella.moneymanageapi.stocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Alpha Vantage, the first source of prices (key in {@code MONEY_PRICES_API_KEY}). The free plan allows 25 requests a
 * day and one a second, for the whole site: every request goes through {@link #call}, which keeps them at least
 * {@link #SPACING} apart (callers queue on it) and stops at {@code app.prices.daily-limit} a day (UTC), or as soon as
 * Alpha Vantage says the limit is reached. Without a key it is off and every call fails at once.
 */
@Component
public class AlphaVantage {

  public static final Duration TIMEOUT = Duration.ofSeconds(5);
  static final Duration SPACING = Duration.ofMillis(1100);

  private static final Logger log = LoggerFactory.getLogger(AlphaVantage.class);

  /** One result of SYMBOL_SEARCH. */
  public record Match(String symbol, String name, String region, Currency currency) {
  }

  private final String base;
  private final String key;
  private final int dailyLimit;
  private final Clock clock;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final ObjectMapper json = new ObjectMapper();

  private LocalDate day;
  private int usedToday;
  private boolean exhaustedToday;
  private Instant lastCall = Instant.EPOCH;

  @Autowired
  public AlphaVantage(@Value("${app.prices.alphavantage-url:https://www.alphavantage.co}") String base,
                      @Value("${app.prices.alphavantage-key:}") String key,
                      @Value("${app.prices.daily-limit:25}") int dailyLimit) {
    this(base, key, dailyLimit, Clock.systemUTC());
  }

  AlphaVantage(String base, String key, int dailyLimit, Clock clock) {
    this.base = base;
    this.key = key == null ? "" : key.trim();
    this.dailyLimit = dailyLimit;
    this.clock = clock;
  }

  public boolean enabled() {
    return !key.isEmpty();
  }

  /** Requests left today (for the page and the logs). */
  public synchronized int leftToday() {
    rollDay();
    return exhaustedToday ? 0 : Math.max(0, dailyLimit - usedToday);
  }

  /** SYMBOL_SEARCH: the listings whose symbol or name match, with their currency. One request. */
  public List<Match> search(String keywords) throws IOException {
    JsonNode matches = call("function=SYMBOL_SEARCH&keywords=" + encode(keywords)).path("bestMatches");
    List<Match> result = new ArrayList<>();
    for (JsonNode m : matches) {
      String symbol = m.path("1. symbol").asText("");
      if (!symbol.isBlank()) {
        result.add(new Match(symbol, m.path("2. name").asText(""), m.path("4. region").asText(""),
            currency(m.path("8. currency").asText(""))));
      }
    }
    return result;
  }

  /** TIME_SERIES_WEEKLY (free): one price a week since the listing, oldest first. One request. */
  public List<Stock> weekly(String symbol, String isin, Currency currency) throws IOException {
    JsonNode series = call("function=TIME_SERIES_WEEKLY&symbol=" + encode(symbol)).path("Weekly Time Series");
    if (!series.isObject() || series.isEmpty()) {
      throw new IOException("Alpha Vantage: no weekly prices for the symbol");
    }
    List<Stock> result = new ArrayList<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = series.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> week = it.next();
      BigDecimal close = number(week.getValue().path("4. close"));
      if (close == null) {
        continue;
      }
      Timestamp at = Timestamp.valueOf(LocalDate.parse(week.getKey()).atStartOfDay());
      JsonNode v = week.getValue();
      JsonNode volume = v.path("5. volume");
      result.add(new Stock(symbol, isin, at, number(v.path("1. open")), number(v.path("3. low")),
          number(v.path("2. high")), close, currency, volume.canConvertToLong() || volume.isTextual()
          ? parseLong(volume.asText()) : null));
    }
    result.sort(Comparator.comparing(Stock::getDatetime));
    return result;
  }

  /** One request, after the previous one by at least SPACING, within the daily limit. */
  private synchronized JsonNode call(String query) throws IOException {
    if (!enabled()) {
      throw new IOException("Alpha Vantage: no key");
    }
    rollDay();
    if (exhaustedToday || usedToday >= dailyLimit) {
      throw new IOException("Alpha Vantage: daily limit reached");
    }
    long waitMillis = Duration.between(clock.instant(), lastCall.plus(SPACING)).toMillis();
    if (waitMillis > 0) {
      try {
        Thread.sleep(waitMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while waiting for Alpha Vantage", e);
      }
    }
    usedToday++;
    lastCall = clock.instant();
    HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/query?" + query + "&apikey=" + encode(key)))
        .header("Accept", "application/json")
        .timeout(TIMEOUT)
        .GET()
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while asking Alpha Vantage", e);
    } finally {
      lastCall = clock.instant();
    }
    if (response.statusCode() != 200) {
      throw new IOException("Alpha Vantage: HTTP " + response.statusCode());
    }
    JsonNode body = json.readTree(response.body());
    // over the limit Alpha Vantage answers 200 with a note instead of the data
    String note = body.path("Information").asText(body.path("Note").asText(""));
    if (!note.isEmpty()) {
      String lower = note.toLowerCase(Locale.ROOT);
      if (lower.contains("rate limit") || lower.contains("requests per day")) {
        exhaustedToday = true;
        log.warn("Alpha Vantage: limit reached, no more requests until tomorrow");
        throw new IOException("Alpha Vantage: daily limit reached");
      }
      // e.g. a premium-only function
      throw new IOException("Alpha Vantage: request refused");
    }
    if (body.has("Error Message")) {
      throw new IOException("Alpha Vantage: unknown symbol");
    }
    return body;
  }

  private void rollDay() {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (!today.equals(day)) {
      day = today;
      usedToday = 0;
      exhaustedToday = false;
    }
  }

  private static BigDecimal number(JsonNode node) {
    if (node.isNumber()) {
      return node.decimalValue();
    }
    try {
      return node.isTextual() && !node.asText().isBlank() ? new BigDecimal(node.asText().trim()) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Long parseLong(String s) {
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static Currency currency(String code) {
    try {
      // GBX/GBp are pence, not pounds: better no currency than a wrong one
      return code.isBlank() || !code.equals(code.toUpperCase(Locale.ROOT)) || code.equals("GBX")
          ? null : Currency.getInstance(code);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
