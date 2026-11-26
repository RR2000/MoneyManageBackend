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
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The two Yahoo calls against a local server: what is sent, what is read, what is refused. */
class YahooPricesTests {

  private static final String CHART = """
      {"chart":{"result":[{"meta":{"currency":"EUR","symbol":"IWDA.AS"},
        "timestamp":[1700000000,1700604800,1701209600],
        "indicators":{"quote":[{"open":[80.1,null,82.0],"high":[81,null,83],"low":[79,null,81],
          "close":[80.5,null,82.5],"volume":[100,null,300]}]}}]}}""";

  private HttpServer server;
  private final List<String> userAgents = new ArrayList<>();
  private volatile int chartStatus = 200;
  private volatile String search = "{\"quotes\":[{\"symbol\":\"IWDA.AS\"}]}";

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/finance/search", ex -> {
      userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
      reply(ex, 200, search);
    });
    server.createContext("/v8/finance/chart/IWDA.AS", ex -> {
      userAgents.add(ex.getRequestHeaders().getFirst("User-Agent"));
      reply(ex, chartStatus, CHART);
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }

  private YahooPrices yahoo() {
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    return new YahooPrices(base, base);
  }

  @Test
  void readsTheWeeklyPricesSkippingTheEmptyWeeks() throws IOException {
    YahooPrices.Prices prices = yahoo().weekly("IE00B4L5Y983");
    assertEquals("IWDA.AS", prices.symbol());
    assertEquals(Currency.getInstance("EUR"), prices.currency());
    assertEquals(2, prices.weekly().size());
    Stock first = prices.weekly().getFirst();
    assertEquals("IE00B4L5Y983", first.getIsin());
    assertEquals(0, new BigDecimal("80.5").compareTo(first.getClose()));
    assertEquals(100L, first.getVolume());
    // Yahoo refuses the default Java User-Agent (429): a browser one on every call
    assertEquals(2, userAgents.size());
    userAgents.forEach(ua -> assertTrue(ua.startsWith("Mozilla/5.0"), ua));
  }

  @Test
  void anUnknownIsinOrARefusalIsAnError() {
    search = "{\"quotes\":[]}";
    assertThrows(IOException.class, () -> yahoo().weekly("XX0000000000"));
    search = "{\"quotes\":[{\"symbol\":\"IWDA.AS\"}]}";
    chartStatus = 429;
    IOException e = assertThrows(IOException.class, () -> yahoo().weekly("IE00B4L5Y983"));
    assertTrue(e.getMessage().contains("429"));
  }
}
