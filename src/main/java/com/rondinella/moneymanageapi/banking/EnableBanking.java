package com.rondinella.moneymanageapi.banking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Client of the Enable Banking API (https://enablebanking.com/docs/api/reference/). Every request carries a JWT
 * signed RS256 with the application's key (kid = application id, valid one hour; Enable Banking allows at most 24).
 * Nothing of what goes or comes back is logged: errors carry only Enable Banking's error code and the endpoint pattern.
 */
@Component
public class EnableBanking {

  static final Duration TIMEOUT = Duration.ofSeconds(30);
  static final Duration TOKEN_TTL = Duration.ofHours(1);

  /** A bank (ASPSP) of a country. maximumConsentValidity in seconds, null if unknown. */
  public record Aspsp(String name, String country, String logo, Long maximumConsentValidity) {
  }

  /** An account given by a session. iban may be null (accounts identified otherwise). */
  public record Account(String uid, String identificationHash, String iban, String name, String currency) {
  }

  public record Session(String sessionId, Instant validUntil, List<Account> accounts) {
  }

  public record Balance(BigDecimal amount, String currency, String type, LocalDate date) {
  }

  /** A movement as the bank gives it: amount as given (positive), the sign is in indicator (CRDT, DBIT). */
  public record Tx(String entryReference, BigDecimal amount, String currency, String indicator, String status,
                   LocalDate bookingDate, String text, String counterparty) {
  }

  public record TxPage(List<Tx> transactions, String continuationKey) {
  }

  /** Headers of a user who is there (manual actions); null for the scheduled syncs. */
  public record Psu(String ipAddress, String userAgent) {
  }

  private final BankingSettings settings;
  private final Clock clock;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper json = new ObjectMapper();

  private String token;
  private Instant tokenUntil = Instant.EPOCH;

  @Autowired
  public EnableBanking(BankingSettings settings) {
    this(settings, Clock.systemUTC());
  }

  EnableBanking(BankingSettings settings, Clock clock) {
    this.settings = settings;
    this.clock = clock;
  }

  /** GET /aspsps: the banks of a country that serve private customers. */
  public List<Aspsp> aspsps(String country) throws IOException {
    JsonNode body = call("GET", "/aspsps?country=" + encode(country) + "&psu_type=personal", "/aspsps", null, null);
    List<Aspsp> result = new ArrayList<>();
    for (JsonNode a : body.path("aspsps")) {
      JsonNode mcv = a.path("maximum_consent_validity");
      String name = text(a, "name");
      if (name != null) {
        result.add(new Aspsp(name, text(a, "country"), text(a, "logo"), mcv.canConvertToLong() ? mcv.asLong() : null));
      }
    }
    return result;
  }

  /** POST /auth: the URL of the bank where the user gives the consent. */
  public String startAuthorization(String aspspName, String country, Instant validUntil, String state,
                                   Psu psu) throws IOException {
    ObjectNode body = json.createObjectNode();
    body.putObject("access").put("valid_until",
        OffsetDateTime.ofInstant(validUntil, ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    body.putObject("aspsp").put("name", aspspName).put("country", country);
    body.put("state", state);
    body.put("redirect_url", settings.redirectUrl());
    body.put("psu_type", "personal");
    String url = text(call("POST", "/auth", "/auth", body, psu), "url");
    if (url == null) {
      throw new EnableBankingException(200, "NO_URL", "/auth");
    }
    return url;
  }

  /** POST /sessions with the code of the callback. */
  public Session createSession(String code, Psu psu) throws IOException {
    ObjectNode body = json.createObjectNode().put("code", code);
    JsonNode s = call("POST", "/sessions", "/sessions", body, psu);
    List<Account> accounts = new ArrayList<>();
    for (JsonNode a : s.path("accounts")) {
      String uid = text(a, "uid");
      if (uid != null) {
        accounts.add(new Account(uid, text(a, "identification_hash"), text(a.path("account_id"), "iban"),
            text(a, "name"), text(a, "currency")));
      }
    }
    String sessionId = text(s, "session_id");
    if (sessionId == null) {
      throw new EnableBankingException(200, "NO_SESSION", "/sessions");
    }
    String validUntil = text(s.path("access"), "valid_until");
    Instant until = null;
    try {
      until = validUntil == null ? null : OffsetDateTime.parse(validUntil).toInstant();
    } catch (RuntimeException e) {
      // keep the one asked for
    }
    return new Session(sessionId, until, accounts);
  }

  public void deleteSession(String sessionId, Psu psu) throws IOException {
    call("DELETE", "/sessions/" + encode(sessionId), "/sessions/{id}", null, psu);
  }

  public List<Balance> balances(String uid, Psu psu) throws IOException {
    JsonNode body = call("GET", "/accounts/" + encode(uid) + "/balances", "/accounts/{uid}/balances", null, psu);
    List<Balance> result = new ArrayList<>();
    for (JsonNode b : body.path("balances")) {
      BigDecimal amount = number(b.path("balance_amount").path("amount"));
      if (amount != null) {
        result.add(new Balance(amount, text(b.path("balance_amount"), "currency"), text(b, "balance_type"),
            date(text(b, "reference_date"))));
      }
    }
    return result;
  }

  /**
   * One page of booked movements. dateFrom null: as far back as the bank gives; strategy "longest" on the first
   * import, null otherwise.
   */
  public TxPage transactions(String uid, LocalDate dateFrom, String strategy, String continuationKey,
                             Psu psu) throws IOException {
    StringBuilder q = new StringBuilder("/accounts/").append(encode(uid)).append("/transactions?transaction_status=BOOK");
    if (dateFrom != null) {
      q.append("&date_from=").append(dateFrom);
    }
    if (strategy != null) {
      q.append("&strategy=").append(encode(strategy));
    }
    if (continuationKey != null) {
      q.append("&continuation_key=").append(encode(continuationKey));
    }
    JsonNode body = call("GET", q.toString(), "/accounts/{uid}/transactions", null, psu);
    List<Tx> txs = new ArrayList<>();
    for (JsonNode t : body.path("transactions")) {
      List<String> lines = new ArrayList<>();
      for (JsonNode l : t.path("remittance_information")) {
        if (!l.asText().isBlank()) {
          lines.add(l.asText().trim());
        }
      }
      String indicator = text(t, "credit_debit_indicator");
      String counterparty = "DBIT".equals(indicator) ? text(t.path("creditor"), "name") : text(t.path("debtor"), "name");
      txs.add(new Tx(text(t, "entry_reference"), number(t.path("transaction_amount").path("amount")),
          text(t.path("transaction_amount"), "currency"), indicator, text(t, "status"),
          date(text(t, "booking_date")), String.join(" ", lines), counterparty));
    }
    return new TxPage(txs, text(body, "continuation_key"));
  }

  private JsonNode call(String method, String pathAndQuery, String endpoint, JsonNode body, Psu psu) throws IOException {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.apiUrl() + pathAndQuery))
        .header("Authorization", "Bearer " + jwt())
        .header("Accept", "application/json")
        .timeout(TIMEOUT);
    if (psu != null && psu.ipAddress() != null && !psu.ipAddress().isBlank()) {
      request.header("Psu-Ip-Address", psu.ipAddress());
      if (psu.userAgent() != null && !psu.userAgent().isBlank()) {
        request.header("Psu-User-Agent", psu.userAgent());
      }
    }
    if (body != null) {
      request.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> response;
    try {
      response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EnableBankingException(0, EnableBankingException.UNREACHABLE, endpoint);
    } catch (IOException e) {
      throw new EnableBankingException(0, EnableBankingException.UNREACHABLE, endpoint);
    }
    JsonNode parsed;
    try {
      String text = response.body();
      parsed = text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text);
    } catch (IOException e) {
      parsed = json.createObjectNode();
    }
    if (response.statusCode() / 100 != 2) {
      String code = text(parsed, "error");
      throw new EnableBankingException(response.statusCode(),
          code != null && code.matches("[A-Z_]{1,60}") ? code : "HTTP_" + response.statusCode(), endpoint);
    }
    return parsed;
  }

  private synchronized String jwt() {
    Instant now = clock.instant();
    if (token == null || now.isAfter(tokenUntil.minus(Duration.ofMinutes(5)))) {
      Instant exp = now.plus(TOKEN_TTL);
      token = Jwts.builder()
          .header().add("typ", "JWT").keyId(settings.appId()).and()
          .issuer("enablebanking.com")
          .audience().add("api.enablebanking.com").and()
          .issuedAt(Date.from(now))
          .expiration(Date.from(exp))
          .signWith(settings.privateKey(), Jwts.SIG.RS256)
          .compact();
      tokenUntil = exp;
    }
    return token;
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.path(field);
    return v.isValueNode() && !v.isNull() && !v.asText().isBlank() ? v.asText() : null;
  }

  private static BigDecimal number(JsonNode node) {
    try {
      if (node.isNumber()) {
        return node.decimalValue();
      }
      return node.isTextual() && !node.asText().isBlank() ? new BigDecimal(node.asText().trim()) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static LocalDate date(String s) {
    try {
      return s == null ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
