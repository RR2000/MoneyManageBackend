package com.rondinella.moneymanageapi.stocks;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenFigiTests {

  private HttpServer server;
  private volatile String answer;
  private volatile String request;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v3/mapping", ex -> {
      request = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, bytes.length);
      ex.getResponseBody().write(bytes);
      ex.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private OpenFigi figi() {
    return new OpenFigi("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @Test
  void readsTickersAndExchanges() throws IOException {
    answer = """
        [{"data":[{"figi":"BBG000PH9DS6","name":"ISHARES CORE MSCI WORLD","ticker":"IWDA","exchCode":"LN"},
                  {"figi":"BBG000P71QK5","name":"ISHARES CORE MSCI WORLD","ticker":"IWDA","exchCode":"NA"},
                  {"figi":"BBG000PH98P0","name":"ISHARES CORE MSCI WORLD","ticker":"SWDA","exchCode":"LN"}]}]""";
    List<OpenFigi.Listing> listings = figi().listings("IE00B4L5Y983");
    assertEquals(3, listings.size());
    assertEquals(new OpenFigi.Listing("IWDA", "NA", "ISHARES CORE MSCI WORLD"), listings.get(1));
    assertTrue(request.contains("\"idType\":\"ID_ISIN\"") && request.contains("\"idValue\":\"IE00B4L5Y983\""), request);
  }

  @Test
  void anUnknownIsinHasNoListings() throws IOException {
    answer = "[{\"warning\":\"No identifier found.\"}]";
    assertTrue(figi().listings("XX0000000000").isEmpty());
  }
}
