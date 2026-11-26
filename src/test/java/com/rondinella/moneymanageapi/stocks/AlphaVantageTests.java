package com.rondinella.moneymanageapi.stocks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Alpha Vantage against a local server: what is read, one request a second, the daily limit. */
class AlphaVantageTests {

  private static final String SEARCH = """
      {"bestMatches":[
        {"1. symbol":"IWDA.LON","2. name":"iShares Core MSCI World","4. region":"United Kingdom","8. currency":"USD"},
        {"1. symbol":"IWDA.AMS","2. name":"iShares Core MSCI World","4. region":"Amsterdam","8. currency":"EUR"}]}""";
  private static final String WEEKLY = """
      {"Meta Data":{"1. Information":"Weekly Prices (open, high, low, close) and Volumes","2. Symbol":"IWDA.AMS"},
       "Weekly Time Series":{
        "2026-11-20":{"1. open":"120.0","2. high":"123.0","3. low":"119.0","4. close":"122.5","5. volume":"1000"},
        "2026-11-13":{"1. open":"118.0","2. high":"121.0","3. low":"117.0","4. close":"120.1","5. volume":"900"}}}""";

  private HttpServer server;
  private final List<String> queries = Collections.synchronizedList(new ArrayList<>());
  private final List<Long> times = Collections.synchronizedList(new ArrayList<>());
  private volatile String next;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/query", ex -> {
      String q = ex.getRequestURI().getRawQuery();
      queries.add(q);
      times.add(System.currentTimeMillis());
      reply(ex, next != null ? next : q.contains("SYMBOL_SEARCH") ? SEARCH : WEEKLY);
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static void reply(HttpExchange ex, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(200, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }

  private AlphaVantage av(String key, int dailyLimit) {
    return new AlphaVantage("http://127.0.0.1:" + server.getAddress().getPort(), key, dailyLimit, Clock.systemUTC());
  }

  @Test
  void readsTheSearchAndTheWeeklyPricesOldestFirst() throws IOException {
    AlphaVantage av = av("test-key", 25);
    List<AlphaVantage.Match> matches = av.search("IWDA");
    assertEquals(List.of("IWDA.LON", "IWDA.AMS"), matches.stream().map(AlphaVantage.Match::symbol).toList());
    assertEquals(Currency.getInstance("EUR"), matches.get(1).currency());

    List<Stock> weekly = av.weekly("IWDA.AMS", "IE00B4L5Y983", Currency.getInstance("EUR"));
    assertEquals(2, weekly.size());
    assertEquals("2026-11-13", weekly.getFirst().getDatetime().toLocalDateTime().toLocalDate().toString());
    assertEquals(0, new BigDecimal("122.5").compareTo(weekly.getLast().getClose()));
    assertEquals("IE00B4L5Y983", weekly.getLast().getIsin());
    assertEquals(1000L, weekly.getLast().getVolume());
    assertTrue(queries.getFirst().contains("function=SYMBOL_SEARCH&keywords=IWDA&apikey=test-key"), queries.getFirst());
  }

  @Test
  void oneRequestASecondAndNoMoreThanTheDailyLimit() throws IOException {
    AlphaVantage av = av("test-key", 3);
    av.search("A");
    av.search("B");
    av.search("C");
    assertTrue(times.get(1) - times.get(0) >= 1000, "too close: " + (times.get(1) - times.get(0)) + " ms");
    assertTrue(times.get(2) - times.get(1) >= 1000);
    assertEquals(0, av.leftToday());
    IOException e = assertThrows(IOException.class, () -> av.search("D"));
    assertTrue(e.getMessage().contains("limit"));
    assertEquals(3, queries.size(), "the fourth request must not leave");
  }

  @Test
  void aRateLimitNoteStopsTheRequestsForTheDay() throws IOException {
    AlphaVantage av = av("test-key", 25);
    next = "{\"Information\":\"We have detected your API key and our standard API rate limit is 25 requests per day.\"}";
    assertThrows(IOException.class, () -> av.search("IWDA"));
    next = null;
    assertThrows(IOException.class, () -> av.search("IWDA"));
    assertEquals(1, queries.size());
    assertEquals(0, av.leftToday());
  }

  @Test
  void unknownSymbolOrNoKey() {
    next = "{\"Error Message\":\"Invalid API call.\"}";
    assertThrows(IOException.class, () -> av("test-key", 25).weekly("NOPE", "X", null));
    AlphaVantage off = av("", 25);
    assertFalse(off.enabled());
    assertThrows(IOException.class, () -> off.search("IWDA"));
    assertEquals(1, queries.size());
  }
}
