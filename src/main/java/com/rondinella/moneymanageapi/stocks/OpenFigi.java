package com.rondinella.moneymanageapi.stocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenFIGI (public, no key): the listings of an ISIN, as ticker and exchange code ("LN" London, "NA" Amsterdam...).
 * Without a key the limit is low (25 requests a minute): the answers are kept for good ({@link IsinListings}).
 */
@Component
public class OpenFigi {

  public static final Duration TIMEOUT = Duration.ofSeconds(5);

  /** One listing of the ISIN. */
  public record Listing(String ticker, String exchange, String name) {
  }

  private final String base;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
  private final ObjectMapper json = new ObjectMapper();

  @Autowired
  public OpenFigi(@Value("${app.prices.openfigi-url:https://api.openfigi.com}") String base) {
    this.base = base;
  }

  /** The listings of the ISIN; empty if OpenFIGI does not know it. */
  public List<Listing> listings(String isin) throws IOException {
    ArrayNode body = json.createArrayNode();
    body.addObject().put("idType", "ID_ISIN").put("idValue", isin);
    HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v3/mapping"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .timeout(TIMEOUT)
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while asking OpenFIGI", e);
    }
    if (response.statusCode() != 200) {
      throw new IOException("OpenFIGI: HTTP " + response.statusCode());
    }
    List<Listing> result = new ArrayList<>();
    for (JsonNode d : json.readTree(response.body()).path(0).path("data")) {
      String ticker = d.path("ticker").asText("");
      String exchange = d.path("exchCode").asText("");
      if (!ticker.isBlank() && !exchange.isBlank()) {
        result.add(new Listing(ticker, exchange, d.path("name").asText("")));
      }
    }
    return result;
  }
}
