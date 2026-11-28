package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.auth.AuthService;
import com.rondinella.moneymanageapi.auth.mail.Mailer;
import com.rondinella.moneymanageapi.banking.BankAuthStates;
import com.rondinella.moneymanageapi.banking.BankingService;
import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import com.rondinella.moneymanageapi.banktransactions.BankTransaction;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionDto;
import com.rondinella.moneymanageapi.tenant.TenantSchemas;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeType;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Open banking against a local fake of Enable Banking: who may use it, the state of the callback, pages of movements,
 * two syncs that add nothing, matching with rows from a file, rate limits and ended consents, the expiry mail, and
 * nothing secret in the logs. All the data is made up.
 */
@SpringBootTest(properties = {
    "app.banking.app-id=" + BankingTests.APP_ID,
    "app.banking.users=" + BankingTests.ALICE_ID,
    "app.web-url=https://money.example.org",
    "app.mail.host=smtp.example.org", "app.mail.username=user", "app.mail.password=secret",
    "app.mail.from=noreply@example.org",
    "app.auth.max-registrations=10000"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(ManualMovements.class)
class BankingTests {

  static final long ALICE_ID = 990001;
  static final String APP_ID = "00000000-test-app0-0000-000000000000";
  static final String SESSION_ID = "sess-5b1c0e7a-fake";
  static final String UID = "uid-0d93-fake";
  static final String IBAN = "IT60X0542811101000000123456";
  static final String CODE = "code-a81f-fake";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final KeyPair KEYS = keys();
  private static final String KEY_B64 = Base64.getEncoder().encodeToString(("-----BEGIN PRIVATE KEY-----\n"
      + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPrivate().getEncoded())
      + "\n-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII));

  /** The fake Enable Banking: requests seen, answers by path. */
  record Seen(String method, String path, String query, Map<String, String> headers, String body) {
  }

  static final List<Seen> SEEN = Collections.synchronizedList(new ArrayList<>());
  /** continuation_key (or "" for the first page) -> page of /transactions */
  static final Map<String, String> PAGES = Collections.synchronizedMap(new HashMap<>());
  static final AtomicReference<String> BALANCES = new AtomicReference<>();
  /** Next error of the data endpoints: status and Enable Banking error code. */
  static final AtomicReference<String[]> ERROR = new AtomicReference<>();
  /** The application refused (wrong key or app id, app not active): every request is a 401 with only a message. */
  static final AtomicBoolean APP_REFUSED = new AtomicBoolean();
  static final String APP_REFUSED_MESSAGE = "Application is not active, see the control panel";
  static final HttpServer SERVER = server();

  @Autowired
  ManualMovements movements;
  @Autowired
  MockMvc mvc;
  @Autowired
  JdbcTemplate jdbc;
  @Autowired
  AuthService authService;
  @Autowired
  TenantSchemas tenantSchemas;
  @Autowired
  BankingService bankingService;
  @MockitoBean
  Mailer mailer;

  private static String alice;
  private static final String SCHEMA = "u" + ALICE_ID;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.banking.api-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    registry.add("app.banking.private-key-b64", () -> KEY_B64);
  }

  @AfterAll
  static void stopServer() {
    SERVER.stop(0);
  }

  private static KeyPair keys() {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      return g.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer server() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext("/", BankingTests::handle);
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void handle(HttpExchange ex) throws IOException {
    String path = ex.getRequestURI().getPath();
    String query = ex.getRequestURI().getRawQuery();
    Map<String, String> headers = new HashMap<>();
    ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    SEEN.add(new Seen(ex.getRequestMethod(), path, query, headers, body));
    // like the real one: a JWT it does not accept, or an application it does not know, is a 401 with no error code
    String refused = APP_REFUSED.get() ? APP_REFUSED_MESSAGE : jwtProblem(headers.get("authorization"));
    if (refused != null) {
      reply(ex, 401, "{\"code\":401,\"message\":\"" + refused + "\"}");
      return;
    }
    String[] error = ERROR.get();
    if (error != null && path.startsWith("/accounts/")) {
      reply(ex, Integer.parseInt(error[0]), "{\"message\":\"x\",\"code\":" + error[0] + ",\"error\":\"" + error[1] + "\"}");
      return;
    }
    switch (path) {
      case "/aspsps" -> reply(ex, 200, """
          {"aspsps":[{"name":"Banca Finta","country":"IT","logo":"https://example.org/logo.png",
                      "maximum_consent_validity":7776000,"psu_types":["personal"]},
                     {"name":"Altra Banca","country":"IT","psu_types":["personal"]}]}""");
      case "/auth" -> reply(ex, 200, "{\"url\":\"https://bank.example.org/consent\",\"authorization_id\":\"a-1\"}");
      case "/sessions" -> reply(ex, 200, """
          {"session_id":"%s","accounts":[{"account_id":{"iban":"%s"},"uid":"%s","identification_hash":"idh-fake-1",
            "currency":"EUR","name":"Conto finto"}],"aspsp":{"name":"Banca Finta","country":"IT"},
           "access":{"valid_until":"%s"}}""".formatted(SESSION_ID, IBAN, UID,
          Instant.now().plus(Duration.ofDays(90)).toString()));
      case "/accounts/" + UID + "/balances" -> reply(ex, 200, BALANCES.get() != null ? BALANCES.get() : """
          {"balances":[{"name":"x","balance_amount":{"currency":"EUR","amount":"1000.00"},"balance_type":"ITAV",
            "reference_date":"2026-11-20"},
           {"name":"y","balance_amount":{"currency":"EUR","amount":"1234.56"},"balance_type":"CLBD",
            "reference_date":"2026-11-20"}]}""");
      case "/accounts/" + UID + "/transactions" -> {
        String key = "";
        for (String p : query == null ? new String[0] : query.split("&")) {
          if (p.startsWith("continuation_key=")) {
            key = URLDecoder.decode(p.substring(17), StandardCharsets.UTF_8);
          }
        }
        String page = PAGES.get(key);
        reply(ex, 200, page != null ? page : "{\"transactions\":[],\"continuation_key\":null}");
      }
      default -> {
        if (path.startsWith("/sessions/") && ex.getRequestMethod().equals("DELETE")) {
          reply(ex, 200, "{\"message\":\"OK\"}");
        } else {
          reply(ex, 404, "{\"message\":\"no\",\"error\":\"WRONG_REQUEST_PARAMETERS\"}");
        }
      }
    }
  }

  /**
   * What Enable Banking checks of the JWT, null if fine: signed by the application's key, kid = application id,
   * typ JWT, iss enablebanking.com, aud the string api.enablebanking.com (an array is refused), at most 24 hours.
   */
  static String jwtProblem(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      return "JWT is missing";
    }
    String token = authorization.substring(7);
    try {
      Jwts.parser().verifyWith(KEYS.getPublic()).build().parseSignedClaims(token);
    } catch (RuntimeException e) {
      return "JWT signature is not valid";
    }
    String[] parts = token.split("\\.");
    JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
    JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    if (!isString(claims.get("aud"), "api.enablebanking.com")) {
      return "JWT audience is not valid";
    }
    if (!isString(claims.get("iss"), "enablebanking.com")) {
      return "JWT issuer is not valid";
    }
    if (!isString(header.get("kid"), APP_ID) || !isString(header.get("typ"), "JWT")) {
      return "JWT header is not valid";
    }
    JsonNode iat = claims.get("iat");
    JsonNode exp = claims.get("exp");
    if (iat == null || exp == null || !iat.isNumber() || !exp.isNumber()
        || exp.asLong() - iat.asLong() > Duration.ofHours(24).toSeconds()) {
      return "JWT lifetime is not valid";
    }
    return null;
  }

  private static boolean isString(JsonNode node, String value) {
    return node != null && node.getNodeType() == JsonNodeType.STRING && value.equals(node.asString());
  }

  private static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }

  static String tx(String ref, String amount, String indicator, String bookingDate, String text) {
    return """
        {"entry_reference":%s,"transaction_amount":{"currency":"EUR","amount":"%s"},"credit_debit_indicator":"%s",
         "status":"BOOK","booking_date":"%s","value_date":"%s","remittance_information":["%s"],
         "creditor":{"name":"Negozio finto"},"debtor":{"name":"Persona finta"}}"""
        .formatted(ref == null ? "null" : "\"" + ref + "\"", amount, indicator, bookingDate, bookingDate, text);
  }

  static String page(String key, String... txs) {
    return "{\"transactions\":[" + String.join(",", txs) + "],\"continuation_key\":"
        + (key == null ? "null" : "\"" + key + "\"") + "}";
  }

  @BeforeEach
  void reset() {
    SEEN.clear();
    PAGES.clear();
    BALANCES.set(null);
    ERROR.set(null);
    APP_REFUSED.set(false);
    if (alice == null) {
      // the enabled user has a fixed id (app.banking.users is read at startup)
      String name = "bank" + UUID.randomUUID().toString().substring(0, 8);
      jdbc.update("INSERT INTO public.users (id, username, password, role) VALUES (?, ?, 'x', 'USER')", ALICE_ID, name);
      tenantSchemas.migrate(SCHEMA);
      alice = "Bearer " + authService.tokenFor(name);
    }
    jdbc.update("DELETE FROM " + SCHEMA + ".bank_link_account");
    jdbc.update("DELETE FROM " + SCHEMA + ".bank_link");
    jdbc.update("DELETE FROM " + SCHEMA + ".bank_transaction");
    jdbc.update("DELETE FROM " + SCHEMA + ".category_rule");
    jdbc.update("DELETE FROM " + SCHEMA + ".category");
    jdbc.update("DELETE FROM " + SCHEMA + ".daily_balance");
    jdbc.update("UPDATE public.users SET email = NULL WHERE id = ?", ALICE_ID);
  }

  private String register() throws Exception {
    String body = "{\"username\":\"o" + UUID.randomUUID().toString().substring(0, 8) + "\",\"password\":\"password123\"}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return "Bearer " + JSON.readTree(json).get("token").asString();
  }

  private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
    return JSON.readTree(mvc.perform(request.header("Authorization", alice))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }


  private List<Seen> seen(String path) {
    synchronized (SEEN) {
      return SEEN.stream().filter(s -> s.path().equals(path)).toList();
    }
  }

  /** The whole link: POST /links, the state the fake bank received, the callback. Returns the bank account id. */
  private long link() throws Exception {
    JsonNode started = json(post("/api/banking/links").contentType(MediaType.APPLICATION_JSON)
        .content("{\"aspsp\":\"Banca Finta\",\"country\":\"IT\"}"));
    assertEquals("https://bank.example.org/consent", started.get("url").asString());
    String state = JSON.readTree(seen("/auth").getLast().body()).get("state").asString();
    mvc.perform(get("/api/banking/callback").param("code", CODE).param("state", state))
        .andExpect(status().isFound()).andExpect(header().string("Location", "/banks?result=ok"));
    bankingService.waitForImports();
    return jdbc.queryForObject("SELECT id FROM " + SCHEMA + ".bank_link_account", Long.class);
  }

  private void associate(long accountId, String moneyAccount) throws Exception {
    json(put("/api/banking/accounts/" + accountId).contentType(MediaType.APPLICATION_JSON)
        .content("{\"moneyAccount\":\"" + moneyAccount + "\"}"));
    bankingService.waitForImports();
  }

  private int rows(String account) {
    return jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".bank_transaction WHERE account = ?", Integer.class,
        account);
  }

  @Test
  void otherUsersGet404AndTheAccountSaysWhoHasIt() throws Exception {
    String bob = register();
    for (MockHttpServletRequestBuilder r : List.of(get("/api/banking/links"), get("/api/banking/aspsps"),
        post("/api/banking/sync"), delete("/api/banking/links/1"))) {
      mvc.perform(r.header("Authorization", bob)).andExpect(status().isNotFound());
    }
    mvc.perform(put("/api/banking/accounts/1").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
        .content("{\"moneyAccount\":\"x\"}")).andExpect(status().isNotFound());
    mvc.perform(post("/api/banking/links").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
        .content("{\"aspsp\":\"Banca Finta\",\"country\":\"IT\"}")).andExpect(status().isNotFound());
    mvc.perform(get("/api/banking/links")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/account").header("Authorization", bob)).andExpect(jsonPath("$.banking").value(false));
    mvc.perform(get("/api/account").header("Authorization", alice)).andExpect(jsonPath("$.banking").value(true));
    assertTrue(SEEN.isEmpty(), "nothing must reach the bank");
  }

  @Test
  void banksOfACountryWithTheirConsentLength() throws Exception {
    JsonNode list = json(get("/api/banking/aspsps").param("country", "it"));
    assertEquals(2, list.size());
    assertEquals("Altra Banca", list.get(0).get("name").asString());
    assertEquals("Banca Finta", list.get(1).get("name").asString());
    assertEquals(7776000, list.get(1).get("maximumConsentValidity").asLong());
    assertEquals("https://example.org/logo.png", list.get(1).get("logo").asString());
    mvc.perform(get("/api/banking/aspsps").param("country", "ITA").header("Authorization", alice))
        .andExpect(status().isBadRequest()).andExpect(status().reason("INVALID_COUNTRY"));
    mvc.perform(post("/api/banking/links").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
            .content("{\"aspsp\":\"Nessuna\",\"country\":\"IT\"}"))
        .andExpect(status().isBadRequest()).andExpect(status().reason("UNKNOWN_ASPSP"));
  }

  @Test
  void theJwtHasTheAudienceAsAStringAsEnableBankingWants() throws Exception {
    json(get("/api/banking/aspsps").param("country", "es"));
    String authorization = seen("/aspsps").getLast().headers().get("authorization");
    assertNull(jwtProblem(authorization));
    String[] parts = authorization.substring(7).split("\\.");
    JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
    JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    assertEquals(JsonNodeType.STRING, claims.get("aud").getNodeType(), claims.toString());
    assertEquals("api.enablebanking.com", claims.get("aud").asString());
    assertEquals("enablebanking.com", claims.get("iss").asString());
    assertEquals(APP_ID, header.get("kid").asString());
    assertEquals("JWT", header.get("typ").asString());
    assertEquals("RS256", header.get("alg").asString());
    assertTrue(claims.get("exp").asLong() - claims.get("iat").asLong() <= Duration.ofHours(24).toSeconds());

    // the fake refuses an array audience like the real one (jjwt's audience().add() writes an array)
    Instant now = Instant.now();
    String withArray = Jwts.builder().header().add("typ", "JWT").keyId(APP_ID).and()
        .issuer("enablebanking.com").audience().add("api.enablebanking.com").and()
        .issuedAt(java.util.Date.from(now)).expiration(java.util.Date.from(now.plusSeconds(3600)))
        .signWith(KEYS.getPrivate(), Jwts.SIG.RS256).compact();
    HttpResponse<String> refused = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort() + "/aspsps?country=IT"))
        .header("Authorization", "Bearer " + withArray).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(401, refused.statusCode());
    assertEquals("JWT audience is not valid", JSON.readTree(refused.body()).get("message").asString());
  }

  @Test
  void aRefusedApplicationIsHttp401AndItsMessageStaysOutOfTheLogs(CapturedOutput output) throws Exception {
    APP_REFUSED.set(true);
    mvc.perform(get("/api/banking/aspsps").param("country", "fr").header("Authorization", alice))
        .andExpect(status().isBadGateway()).andExpect(status().reason("HTTP_401"));
    assertThat(output.getAll()).contains("HTTP_401").doesNotContain(APP_REFUSED_MESSAGE);
  }

  @Test
  void theAuthorizationAsksTheLongestConsentWithASignedJwtAndAOneTimeState() throws Exception {
    Instant before = Instant.now();
    json(post("/api/banking/links").contentType(MediaType.APPLICATION_JSON)
        .content("{\"aspsp\":\"Banca Finta\",\"country\":\"IT\"}"));
    Seen auth = seen("/auth").getLast();
    JsonNode body = JSON.readTree(auth.body());
    assertEquals("https://money.example.org/api/banking/callback", body.get("redirect_url").asString());
    assertEquals("Banca Finta", body.get("aspsp").get("name").asString());
    assertEquals("personal", body.get("psu_type").asString());
    Instant validUntil = Instant.parse(body.get("access").get("valid_until").asString());
    assertTrue(Math.abs(Duration.between(before.plusSeconds(7776000), validUntil).toSeconds()) < 120, validUntil.toString());
    assertEquals("127.0.0.1", auth.headers().get("psu-ip-address"));

    Jws<Claims> jwt = Jwts.parser().verifyWith(KEYS.getPublic()).build()
        .parseSignedClaims(auth.headers().get("authorization").substring(7));
    assertEquals("00000000-test-app0-0000-000000000000", jwt.getHeader().getKeyId());
    assertEquals("enablebanking.com", jwt.getPayload().getIssuer());
    assertTrue(jwt.getPayload().getAudience().contains("api.enablebanking.com"));
    assertTrue(Duration.between(jwt.getPayload().getIssuedAt().toInstant(), jwt.getPayload().getExpiration().toInstant())
        .compareTo(Duration.ofHours(24)) <= 0);

    String state = body.get("state").asString();
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM public.bank_auth_state WHERE state_hash = ?", Integer.class,
        BankAuthStates.hash(state)));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public.bank_auth_state WHERE state_hash = ?", Integer.class,
        state), "the state is kept only as a hash");

    // unknown state, then the good one, then the same again
    mvc.perform(get("/api/banking/callback").param("code", CODE).param("state", "nope"))
        .andExpect(status().isFound()).andExpect(header().string("Location", "/banks?result=error"));
    mvc.perform(get("/api/banking/callback").param("code", CODE).param("state", state))
        .andExpect(status().isFound()).andExpect(header().string("Location", "/banks?result=ok"));
    mvc.perform(get("/api/banking/callback").param("code", CODE).param("state", state))
        .andExpect(status().isFound()).andExpect(header().string("Location", "/banks?result=error"));
    assertEquals(1, seen("/sessions").size());
    assertEquals(CODE, JSON.readTree(seen("/sessions").getFirst().body()).get("code").asString());

    // an expired state
    json(post("/api/banking/links").contentType(MediaType.APPLICATION_JSON)
        .content("{\"aspsp\":\"Banca Finta\",\"country\":\"IT\"}"));
    String late = JSON.readTree(seen("/auth").getLast().body()).get("state").asString();
    jdbc.update("UPDATE public.bank_auth_state SET expires_at = now() - interval '1 second' WHERE state_hash = ?",
        BankAuthStates.hash(late));
    mvc.perform(get("/api/banking/callback").param("code", CODE).param("state", late))
        .andExpect(header().string("Location", "/banks?result=error"));
    // the user said no at the bank
    json(post("/api/banking/links").contentType(MediaType.APPLICATION_JSON)
        .content("{\"aspsp\":\"Banca Finta\",\"country\":\"IT\"}"));
    String refused = JSON.readTree(seen("/auth").getLast().body()).get("state").asString();
    mvc.perform(get("/api/banking/callback").param("error", "access_denied").param("state", refused))
        .andExpect(header().string("Location", "/banks?result=error"));
    assertEquals(1, seen("/sessions").size());
  }

  @Test
  void aLinkShowsItsAccountWithTheMaskedIbanAndTheBalance() throws Exception {
    long accountId = link();
    JsonNode links = json(get("/api/banking/links"));
    assertEquals(1, links.size());
    JsonNode l = links.get(0);
    assertEquals("Banca Finta", l.get("aspsp").asString());
    assertEquals("ACTIVE", l.get("status").asString());
    assertFalse(l.get("validUntil").isNull());
    assertFalse(l.toString().contains(SESSION_ID));
    assertFalse(l.toString().contains(UID));
    JsonNode a = l.get("accounts").get(0);
    assertEquals(accountId, a.get("id").asLong());
    assertEquals("IT **** 3456", a.get("iban").asString());
    assertEquals("EUR", a.get("currency").asString());
    assertTrue(a.get("moneyAccount").isNull());
    assertEquals(0, new BigDecimal("1234.56").compareTo(a.get("balance").get("amount").decimalValue()), "CLBD first");
    assertEquals("CLBD", a.get("balance").get("type").asString());
    assertEquals("2026-11-20", a.get("balance").get("date").asString());
    // no money account yet: no movements asked
    assertTrue(seen("/accounts/" + UID + "/transactions").isEmpty());
  }

  @Test
  void pagesUntilTheKeyIsGoneAlsoThroughAnEmptyOneAndTwoSyncsAddNothing() throws Exception {
    PAGES.put("", page("k1", tx("ref-1", "50.00", "CRDT", "2026-11-02", "Stipendio finto"),
        tx("ref-2", "12.30", "DBIT", "2026-11-03", "Spesa finta")));
    PAGES.put("k1", page("k2"));
    PAGES.put("k2", page(null, tx(null, "4.10", "DBIT", "2026-11-05", "Caffe finto"),
        tx(null, "4.10", "DBIT", "2026-11-05", "Caffe finto"), tx("ref-1", "50.00", "CRDT", "2026-11-02", "Stipendio finto")));
    long accountId = link();
    associate(accountId, "Banca_finta");
    assertEquals(4, rows("Banca_finta"));

    List<Seen> calls = seen("/accounts/" + UID + "/transactions");
    assertEquals(3, calls.size());
    assertTrue(calls.get(0).query().contains("strategy=longest"), calls.get(0).query());
    assertFalse(calls.get(0).query().contains("date_from"), "a new money account: as far back as the bank goes");
    assertTrue(calls.get(0).query().contains("transaction_status=BOOK"));
    assertTrue(calls.get(1).query().contains("continuation_key=k1"));
    assertTrue(calls.get(2).query().contains("continuation_key=k2"));

    assertEquals(List.of("50.0000", "-12.3000", "-4.1000", "-4.1000"), jdbc.queryForList(
        "SELECT amount::text FROM " + SCHEMA + ".bank_transaction WHERE account = 'Banca_finta' ORDER BY datetime, id",
        String.class));
    assertEquals(4, jdbc.queryForObject("SELECT count(DISTINCT external_ref) FROM " + SCHEMA
        + ".bank_transaction WHERE source = 'BANK'", Integer.class), "two equal coffees, two ids");
    // the balance of the bank is the balance after the last movement
    assertEquals(0, new BigDecimal("1234.56").compareTo(jdbc.queryForObject("SELECT cumulative_amount FROM " + SCHEMA
        + ".bank_transaction WHERE account = 'Banca_finta' ORDER BY datetime DESC, id DESC LIMIT 1", BigDecimal.class)));

    SEEN.clear();
    JsonNode result = json(post("/api/banking/sync"));
    assertEquals(0, result.get("imported").asInt());
    assertEquals(4, rows("Banca_finta"));
    Seen again = seen("/accounts/" + UID + "/transactions").getFirst();
    assertTrue(again.query().contains("date_from=2026-10-31"), "last booking date - 5 days: " + again.query());
    assertFalse(again.query().contains("strategy"));
    assertEquals("127.0.0.1", again.headers().get("psu-ip-address"));

    // the scheduled sync: no PSU headers, still nothing new
    SEEN.clear();
    bankingService.scheduledSync();
    assertEquals(4, rows("Banca_finta"));
    Seen scheduled = seen("/accounts/" + UID + "/transactions").getFirst();
    assertNull(scheduled.headers().get("psu-ip-address"));
    assertNull(scheduled.headers().get("psu-user-agent"));
    assertEquals("BANK", jdbc.queryForObject("SELECT DISTINCT source FROM " + SCHEMA + ".bank_transaction", String.class));
  }

  @Test
  void anExistingMoneyAccountIsImportedFromTheDayAfterItsLastMovement() throws Exception {
    jdbc.update("INSERT INTO " + SCHEMA + ".bank_transaction (account, datetime, description, amount, fee, currency) "
        + "VALUES ('Da_file', '2026-11-03 10:00', 'Riga finta', -7.00, 0, 'EUR')");
    PAGES.put("", page(null, tx("r-old", "7.00", "DBIT", "2026-11-03", "Riga finta"),
        tx("r-new", "9.00", "DBIT", "2026-11-04", "Nuova finta")));
    long accountId = link();
    associate(accountId, "Da_file");
    assertEquals(2, rows("Da_file"));
    Seen call = seen("/accounts/" + UID + "/transactions").getFirst();
    assertTrue(call.query().contains("date_from=2026-11-04"), call.query());
    assertTrue(call.query().contains("strategy=longest"));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".bank_transaction WHERE external_ref = 'r-new'",
        Integer.class));
  }

  @Test
  void movementsFromTheBankMatchTheRowsOfAFile() throws Exception {
    long accountId = link();
    associate(accountId, "Con_file");
    assertEquals(1, seen("/accounts/" + UID + "/transactions").size());

    // rows from a file, uploaded after the link
    String file = """
        [{"account":"Con_file","datetime":"2026-11-10T09:00:00.000+00:00","description":"SUPERMERCATO FINTO","amount":-12.50,"currency":"EUR"},
         {"account":"Con_file","datetime":"2026-11-11T00:00:00.000+00:00","description":"BENZINA FINTA","amount":-30.00,"currency":"EUR"},
         {"account":"Con_file","datetime":"2026-11-12T08:00:00.000+00:00","description":"CAFFE 1","amount":-1.50,"currency":"EUR"},
         {"account":"Con_file","datetime":"2026-11-12T09:00:00.000+00:00","description":"CAFFE 2","amount":-1.50,"currency":"EUR"}]""";
    movements.add(alice, file);
    assertEquals(4, rows("Con_file"));

    PAGES.put("", page(null,
        tx("b-1", "12.50", "DBIT", "2026-11-10", "Pagamento supermercato"),   // same day: matched
        tx("b-2", "30.00", "DBIT", "2026-11-10", "Pagamento benzina"),        // a day before the file: matched
        tx("b-3", "1.50", "DBIT", "2026-11-12", "Caffe"),                     // two rows, one movement: ambiguous
        tx("b-4", "100.00", "CRDT", "2026-11-13", "Bonifico finto")));        // new
    JsonNode result = json(post("/api/banking/sync"));
    assertEquals(1, result.get("imported").asInt());
    assertEquals(2, result.get("matched").asInt());
    assertEquals(1, result.get("ambiguous").asInt());
    assertEquals(1, result.get("links").get(0).get("lastAmbiguous").asInt());
    assertEquals(5, rows("Con_file"));
    assertEquals("b-1", jdbc.queryForObject("SELECT external_ref FROM " + SCHEMA
        + ".bank_transaction WHERE description = 'SUPERMERCATO FINTO'", String.class));
    assertEquals("b-2", jdbc.queryForObject("SELECT external_ref FROM " + SCHEMA
        + ".bank_transaction WHERE description = 'BENZINA FINTA'", String.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
        + ".bank_transaction WHERE description LIKE 'CAFFE%' AND external_ref IS NOT NULL", Integer.class));
    assertEquals("MANUAL", jdbc.queryForObject("SELECT source FROM " + SCHEMA
        + ".bank_transaction WHERE description = 'BENZINA FINTA'", String.class));

    // the movements API says where each row comes from
    mvc.perform(post("/api/money-accounts/{id}/movements", AccountPages.idOf("Con_file")).header("Authorization", alice)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(jsonPath("$.items[?(@.description == 'Bonifico finto')].source").value("BANK"))
        .andExpect(jsonPath("$.items[?(@.description == 'CAFFE 1')].source").value("MANUAL"));

    // the same again: nothing new, nothing matched twice
    JsonNode again = json(post("/api/banking/sync"));
    assertEquals(0, again.get("imported").asInt());
    assertEquals(0, again.get("matched").asInt());
    assertEquals(5, rows("Con_file"));
  }

  @Test
  void theRulesGiveTheirCategoryToTheMovementsFromTheBankAlsoTheNextOnes() throws Exception {
    long bills = JSON.readTree(mvc.perform(post("/api/categories").header("Authorization", alice)
        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bollette finte\",\"fixed\":true}"))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(post("/api/categories/rules").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
            .content("{\"categoryId\":" + bills + ",\"pattern\":\"luce finta\",\"direction\":\"OUT\"}"))
        .andExpect(status().isCreated());
    PAGES.put("", page(null, tx("c-1", "60.00", "DBIT", "2026-10-05", "Bolletta luce finta"),
        tx("c-2", "5.00", "DBIT", "2026-10-06", "Spesa finta")));
    long accountId = link();
    associate(accountId, "Con_regole");
    assertEquals(List.of("Bolletta luce finta"), jdbc.queryForList("SELECT description FROM " + SCHEMA
        + ".bank_transaction WHERE category_id = ? AND category_source = 'RULE'", String.class, bills));

    // the next sync: the new movement gets it too
    PAGES.put("", page(null, tx("c-3", "62.00", "DBIT", "2026-11-05", "BOLLETTA LUCE  FINTA nov")));
    json(post("/api/banking/sync"));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".bank_transaction WHERE category_id = ?",
        Integer.class, bills));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
        + ".bank_transaction WHERE category_id IS NULL", Integer.class));
  }

  @Test
  void aRateLimitStopsTheSyncsForSixHours() throws Exception {
    long accountId = link();
    associate(accountId, "Limitato");
    ERROR.set(new String[] {"429", "ASPSP_RATE_LIMIT_EXCEEDED"});
    JsonNode result = json(post("/api/banking/sync"));
    assertEquals("ASPSP_RATE_LIMIT_EXCEEDED", result.get("links").get(0).get("lastError").asString());
    assertEquals("ACTIVE", result.get("links").get(0).get("status").asString());
    Instant retry = jdbc.queryForObject("SELECT retry_after FROM " + SCHEMA + ".bank_link", java.sql.Timestamp.class)
        .toInstant();
    assertTrue(Duration.between(Instant.now(), retry).toMinutes() > 355, retry.toString());

    ERROR.set(null);
    SEEN.clear();
    json(post("/api/banking/sync"));
    bankingService.scheduledSync();
    assertTrue(SEEN.isEmpty(), "no request before the 6 hours");
  }

  @Test
  void anEndedConsentMarksTheLinkExpiredOrRevoked() throws Exception {
    long accountId = link();
    associate(accountId, "Scaduto");
    ERROR.set(new String[] {"401", "EXPIRED_SESSION"});
    JsonNode result = json(post("/api/banking/sync"));
    assertEquals("EXPIRED", result.get("links").get(0).get("status").asString());
    assertEquals("EXPIRED_SESSION", result.get("links").get(0).get("lastError").asString());
    SEEN.clear();
    json(post("/api/banking/sync"));
    assertTrue(SEEN.isEmpty(), "an expired link is not asked again");

    jdbc.update("UPDATE " + SCHEMA + ".bank_link SET status = 'ACTIVE', last_error = NULL");
    ERROR.set(new String[] {"401", "REVOKED_SESSION"});
    assertEquals("REVOKED", json(post("/api/banking/sync")).get("links").get(0).get("status").asString());

    // valid_until passed: expired without asking the bank
    ERROR.set(null);
    jdbc.update("UPDATE " + SCHEMA + ".bank_link SET status = 'ACTIVE', valid_until = now() - interval '1 minute'");
    SEEN.clear();
    assertEquals("EXPIRED", json(get("/api/banking/links")).get(0).get("status").asString());
    json(post("/api/banking/sync"));
    assertTrue(SEEN.isEmpty());
  }

  @Test
  void aMailSevenDaysBeforeTheEndOfTheConsentOnlyOnce() throws Exception {
    link();
    jdbc.update("UPDATE public.users SET email = 'alice@example.com' WHERE id = ?", ALICE_ID);
    jdbc.update("UPDATE " + SCHEMA + ".bank_link SET valid_until = now() + interval '10 days'");
    bankingService.scheduledSync();
    verify(mailer, after(300).never()).send(anyString(), anyString(), anyString());

    jdbc.update("UPDATE " + SCHEMA + ".bank_link SET valid_until = now() + interval '6 days'");
    bankingService.scheduledSync();
    verify(mailer, timeout(5000)).send(eq("alice@example.com"), contains("bank link"),
        argThat(text -> text.contains("Banca Finta") && text.contains("https://money.example.org/banks")
            && text.contains("Ciao")));
    bankingService.scheduledSync();
    verify(mailer, after(300).times(1)).send(anyString(), anyString(), anyString());
  }

  @Test
  void deletingALinkClosesTheConsentAndKeepsTheMovements() throws Exception {
    PAGES.put("", page(null, tx("d-1", "5.00", "DBIT", "2026-11-02", "Riga finta")));
    long accountId = link();
    associate(accountId, "Da_tenere");
    assertEquals(1, rows("Da_tenere"));
    long linkId = jdbc.queryForObject("SELECT id FROM " + SCHEMA + ".bank_link", Long.class);
    mvc.perform(delete("/api/banking/links/" + linkId).header("Authorization", alice)).andExpect(status().isNoContent());
    assertEquals(1, seen("/sessions/" + SESSION_ID).size());
    assertEquals("DELETE", seen("/sessions/" + SESSION_ID).getFirst().method());
    assertEquals(0, json(get("/api/banking/links")).size());
    assertEquals(1, rows("Da_tenere"));
    mvc.perform(delete("/api/banking/links/" + linkId).header("Authorization", alice)).andExpect(status().isNotFound());
  }

  @Test
  void aNewConsentForTheSameAccountKeepsItsMoneyAccountAndReplacesTheOldLink() throws Exception {
    PAGES.put("", page(null, tx("n-1", "5.00", "DBIT", "2026-11-02", "Riga finta")));
    long first = link();
    associate(first, "Rinnovato");
    assertEquals(1, rows("Rinnovato"));
    link();
    JsonNode links = json(get("/api/banking/links"));
    assertEquals("Rinnovato", links.get(0).get("accounts").get(0).get("moneyAccount").asString());
    assertEquals(1, rows("Rinnovato"));
    mvc.perform(put("/api/banking/accounts/" + links.get(0).get("accounts").get(0).get("id").asLong())
            .header("Authorization", alice).contentType(MediaType.APPLICATION_JSON).content("{\"moneyAccount\":\"" + "x".repeat(101) + "\"}"))
        .andExpect(status().isBadRequest()).andExpect(status().reason("INVALID_MONEY_ACCOUNT"));
  }

  @Test
  void nothingSecretReachesTheLogs(CapturedOutput output) throws Exception {
    PAGES.put("", page(null, tx("s-1", "777.77", "DBIT", "2026-11-02", "Descrizione segreta")));
    long accountId = link();
    associate(accountId, "Segreto");
    assertEquals(1, rows("Segreto"));
    ERROR.set(new String[] {"429", "ASPSP_RATE_LIMIT_EXCEEDED"});
    json(post("/api/banking/sync"));
    ERROR.set(null);
    mvc.perform(get("/api/banking/callback").param("code", CODE).param("state", "unknown-state-value"));
    String logs = output.getAll();
    assertThat(logs).contains("ASPSP_RATE_LIMIT_EXCEEDED");
    for (String secret : List.of(SESSION_ID, UID, IBAN, CODE, "777.77", "Descrizione segreta", "unknown-state-value",
        KEY_B64.substring(0, 40), "idh-fake-1")) {
      assertFalse(logs.contains(secret), "in the logs: " + secret);
    }
  }

  private static org.assertj.core.api.AbstractStringAssert<?> assertThat(String s) {
    return org.assertj.core.api.Assertions.assertThat(s);
  }

  @Test
  void theSameDayAndDescriptionTwiceAreTwoRowsAndTheSameFileTwiceAddsNothing() throws Exception {
    String twice = """
        [{"account":"Doppio","datetime":"2026-11-10T00:00:00.000+00:00","description":"PAGAMENTO POS","amount":-2.00,"currency":"EUR"},
         {"account":"Doppio","datetime":"2026-11-10T00:00:00.000+00:00","description":"PAGAMENTO POS","amount":-2.00,"currency":"EUR"}]""";
    for (int i = 0; i < 2; i++) {
      movements.add(alice, twice);
    }
    assertEquals(2, rows("Doppio"));
    // one row with a corrected amount: updates the first, adds nothing (as before the id)
    List<BankTransactionDto> corrected = movements.add(alice, """
        [{"account":"Doppio","datetime":"2026-11-10T00:00:00.000+00:00","description":"PAGAMENTO POS","amount":-3.00,"currency":"EUR"}]""");
    assertEquals(BankTransaction.Source.MANUAL, corrected.getFirst().getSource());
    assertEquals(List.of("-3.0000", "-2.0000"), jdbc.queryForList("SELECT amount::text FROM " + SCHEMA
        + ".bank_transaction WHERE account = 'Doppio' ORDER BY id", String.class));
  }

  @Test
  void theMigrationKeepsEveryRowAndGivesEachAnId() {
    String schema = "mig" + UUID.randomUUID().toString().substring(0, 8);
    org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema)
        .locations("classpath:db/tenant").target("3").load().migrate();
    jdbc.update("INSERT INTO " + schema + ".bank_transaction (account, datetime, description, amount, fee, currency, "
        + "cumulative_amount) VALUES ('A', '2025-01-01 10:00', 'x', -1, 0, 'EUR', 10), "
        + "('A', '2025-01-01 10:00', 'y', -2, 0, 'EUR', 11), ('B', '2025-01-02 00:00', 'x', 5, 0, 'USD', null)");
    // before: the same moment and description is the same row
    assertThrows(Exception.class, () -> jdbc.update("INSERT INTO " + schema
        + ".bank_transaction (account, datetime, description, amount) VALUES ('A', '2025-01-01 10:00', 'x', -1)"));

    tenantSchemas.migrate(schema);
    assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".bank_transaction", Integer.class));
    assertEquals(3, jdbc.queryForObject("SELECT count(DISTINCT id) FROM " + schema + ".bank_transaction", Integer.class));
    assertEquals(List.of("FILE"), jdbc.queryForList("SELECT DISTINCT source FROM " + schema + ".bank_transaction", String.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema
        + ".bank_transaction WHERE external_ref IS NOT NULL", Integer.class));
    assertEquals(0, new BigDecimal("11").compareTo(jdbc.queryForObject("SELECT cumulative_amount FROM " + schema
        + ".bank_transaction WHERE description = 'y'", BigDecimal.class)));
    // after: two movements of the same moment and description are two rows; a bank id only once per account
    jdbc.update("INSERT INTO " + schema + ".bank_transaction (account, datetime, description, amount) "
        + "VALUES ('A', '2025-01-01 10:00', 'x', -1)");
    assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".bank_transaction", Integer.class));
    jdbc.update("INSERT INTO " + schema + ".bank_transaction (account, datetime, description, amount, source, external_ref) "
        + "VALUES ('A', '2025-01-03', 'z', 1, 'BANK', 'e-1'), ('B', '2025-01-03', 'z', 1, 'BANK', 'e-1')");
    assertThrows(Exception.class, () -> jdbc.update("INSERT INTO " + schema
        + ".bank_transaction (account, datetime, description, amount, source, external_ref) VALUES ('A', '2025-01-04', 'w', 1, 'BANK', 'e-1')"));
    jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
  }
  private void fileRow(String account, String datetime, String description, String amount, String currency) {
    jdbc.update("INSERT INTO " + SCHEMA + ".bank_transaction (account, datetime, description, amount, fee, currency, "
        + "source) VALUES (?, ?::timestamp, ?, ?::numeric, 0, ?, 'FILE')", account, datetime, description, amount,
        currency);
  }

  private BigDecimal decimal(JsonNode node) {
    return node == null || node.isNull() ? null : node.decimalValue();
  }

  private static void assertAmount(String expected, BigDecimal actual) {
    assertNotNull(actual, "expected " + expected);
    assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " != " + actual);
  }

  @Test
  void replaceWithTheBankDataShowsWhatChangesThenDoesItOnlyWithTheToken() throws Exception {
    fileRow("Vecchio", "2026-10-01 10:00", "Riga vecchia finta", "-20.00", "EUR");
    fileRow("Vecchio", "2026-11-02 10:00", "STIPENDIO DA FILE", "50.00", "EUR");
    fileRow("Vecchio", "2026-11-05 10:00", "CAFFE A", "-4.10", "EUR");
    fileRow("Vecchio", "2026-11-05 10:00", "CAFFE B", "-4.10", "EUR");
    fileRow("Vecchio", "2026-11-05 10:00", "CAFFE C", "-4.10", "EUR");
    fileRow("Vecchio", "2026-11-06 10:00", "Doppione finto", "-9.99", "EUR");
    fileRow("Vecchio", "2026-11-07 10:00", "In dollari", "-3.00", "USD");
    PAGES.put("", page(null,
        tx("ref-1", "50.00", "CRDT", "2026-11-02", "Stipendio finto"),
        tx("ref-3", "12.30", "DBIT", "2026-11-03", "Spesa finta"),
        tx("c-1", "4.10", "DBIT", "2026-11-05", "Caffe finto"),
        tx("c-2", "4.10", "DBIT", "2026-11-05", "Caffe finto"),
        tx("ref-4", "100.00", "CRDT", "2026-11-09", "Bonifico finto")));
    long accountId = link();
    // an account with rows: the bank's movements arrive from the day after the last row
    associate(accountId, "Vecchio");
    assertEquals(8, rows("Vecchio"));
    JsonNode account = json(get("/api/banking/links")).get(0).get("accounts").get(0);
    assertEquals("2026-11-09", account.get("bankFrom").asString());
    assertEquals("2026-10-01", account.get("firstDate").asString());
    assertEquals(0, account.get("extraRows").asInt());
    assertEquals(6, account.get("olderRows").asInt(), "the USD row is not counted");

    // the preview reads the whole history and changes nothing
    SEEN.clear();
    JsonNode preview = json(post("/api/banking/accounts/" + accountId + "/replace/preview"));
    Seen read = seen("/accounts/" + UID + "/transactions").getFirst();
    assertTrue(read.query().contains("strategy=longest"), read.query());
    assertFalse(read.query().contains("date_from"), read.query());
    assertEquals("2026-11-02", preview.get("bankFrom").asString());
    assertEquals(2, preview.get("deleted").asInt(), "the third coffee and the double");
    assertEquals(1, preview.get("added").asInt());
    assertEquals(3, preview.get("matched").asInt(), "the salary and two coffees");
    assertEquals(1, preview.get("otherCurrency").asInt());
    assertEquals(1, preview.get("older").asInt());
    assertEquals("EUR", preview.get("currency").asString());
    assertAmount("1234.56", decimal(preview.get("todayBefore")));
    assertAmount("1234.56", decimal(preview.get("todayAfter")));
    assertAmount("1109.85", decimal(preview.get("startBefore")));
    assertAmount("1108.06", decimal(preview.get("startAfter")));
    String token = preview.get("token").asString();
    assertFalse(token.isBlank());
    assertEquals(8, rows("Vecchio"));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
        + ".bank_transaction WHERE external_ref IS NOT NULL", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
        + ".daily_balance WHERE account = 'Vecchio' AND date = '2026-11-06'", Integer.class));

    // a wrong token does nothing
    mvc.perform(post("/api/banking/accounts/" + accountId + "/replace").header("Authorization", alice)
            .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"nope\"}"))
        .andExpect(status().isConflict()).andExpect(status().reason("PREVIEW_EXPIRED"));
    assertEquals(8, rows("Vecchio"));

    SEEN.clear();
    JsonNode done = json(post("/api/banking/accounts/" + accountId + "/replace").contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\"}"));
    assertTrue(SEEN.isEmpty(), "the movements of the preview, the bank is not asked again");
    assertTrue(done.get("token").isNull());
    assertEquals(2, done.get("deleted").asInt());
    assertEquals(1, done.get("added").asInt());
    assertAmount("1108.06", decimal(done.get("startAfter")));
    assertEquals(List.of("Riga vecchia finta", "STIPENDIO DA FILE", "Spesa finta", "CAFFE A", "CAFFE B", "In dollari",
        "Bonifico finto"), jdbc.queryForList("SELECT description FROM " + SCHEMA
        + ".bank_transaction WHERE account = 'Vecchio' ORDER BY datetime, id", String.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
        + ".daily_balance WHERE account = 'Vecchio' AND date = '2026-11-06'", Integer.class),
        "the day of the deleted row has no balance any more");
    assertAmount("1108.06", jdbc.queryForObject("SELECT balance FROM " + SCHEMA
        + ".daily_balance WHERE account = 'Vecchio' AND date = '2026-10-01'", BigDecimal.class));
    mvc.perform(post("/api/banking/accounts/" + accountId + "/replace").header("Authorization", alice)
            .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isConflict()).andExpect(status().reason("PREVIEW_EXPIRED"));

    account = json(get("/api/banking/links")).get(0).get("accounts").get(0);
    assertEquals("2026-11-02", account.get("bankFrom").asString());
    assertEquals(0, account.get("extraRows").asInt());
    assertEquals(1, account.get("olderRows").asInt());

    // a sync adds nothing: every movement of the bank is there once
    assertEquals(0, json(post("/api/banking/sync")).get("imported").asInt());
    assertEquals(7, rows("Vecchio"));

    // the older rows: only with the first day of the bank's history the user saw
    mvc.perform(post("/api/banking/accounts/" + accountId + "/delete-older").header("Authorization", alice)
            .contentType(MediaType.APPLICATION_JSON).content("{\"before\":\"2026-11-03\"}"))
        .andExpect(status().isConflict()).andExpect(status().reason("PREVIEW_EXPIRED"));
    assertEquals(7, rows("Vecchio"));
    JsonNode older = json(post("/api/banking/accounts/" + accountId + "/delete-older")
        .contentType(MediaType.APPLICATION_JSON).content("{\"before\":\"2026-11-02\"}"));
    assertEquals(1, older.get("deleted").asInt());
    assertEquals(6, rows("Vecchio"));
    assertAmount("1234.56", jdbc.queryForObject("SELECT cumulative_amount FROM " + SCHEMA
        + ".bank_transaction WHERE account = 'Vecchio' ORDER BY datetime DESC, id DESC LIMIT 1", BigDecimal.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
        + ".daily_balance WHERE account = 'Vecchio' AND date < '2026-11-02'", Integer.class));
  }

  @Test
  void replaceNeedsAMoneyAccountAndAnActiveLink() throws Exception {
    long accountId = link();
    mvc.perform(post("/api/banking/accounts/" + accountId + "/replace/preview").header("Authorization", alice))
        .andExpect(status().isConflict()).andExpect(status().reason("NO_MONEY_ACCOUNT"));
    associate(accountId, "Scaduto");
    jdbc.update("UPDATE " + SCHEMA + ".bank_link SET status = 'EXPIRED'");
    mvc.perform(post("/api/banking/accounts/" + accountId + "/replace/preview").header("Authorization", alice))
        .andExpect(status().isConflict()).andExpect(status().reason("LINK_NOT_ACTIVE"));
    mvc.perform(post("/api/banking/accounts/999999/replace/preview").header("Authorization", alice))
        .andExpect(status().isNotFound());
    String bob = register();
    mvc.perform(post("/api/banking/accounts/" + accountId + "/replace/preview").header("Authorization", bob))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/banking/accounts/" + accountId + "/replace").header("Authorization", bob)
        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}")).andExpect(status().isNotFound());
    mvc.perform(post("/api/banking/accounts/" + accountId + "/delete-older").header("Authorization", bob)
        .contentType(MediaType.APPLICATION_JSON).content("{\"before\":\"2026-11-02\"}")).andExpect(status().isNotFound());
  }

  @Test
  void reconnectingForTheWholeHistoryImportsAtOnceAsFarBackAsTheBankGoes() throws Exception {
    fileRow("Da_file", "2026-11-03 10:00", "Riga finta", "-7.00", "EUR");
    fileRow("Da_file", "2026-11-03 11:00", "Riga finta doppia", "-7.00", "EUR");
    PAGES.put("", page(null, tx("r-old", "7.00", "DBIT", "2026-11-03", "Riga finta"),
        tx("r-new", "9.00", "DBIT", "2026-11-04", "Nuova finta")));
    long accountId = link();
    associate(accountId, "Da_file");
    assertEquals(3, rows("Da_file"));

    // a plain reconnect goes on from where the import got to
    SEEN.clear();
    link();
    Seen plain = seen("/accounts/" + UID + "/transactions").getFirst();
    assertTrue(plain.query().contains("date_from=2026-11-04"), plain.query());
    assertFalse(plain.query().contains("strategy"), plain.query());
    assertEquals(3, rows("Da_file"));

    // reconnect for the whole history: imported right after the callback, from the oldest day the bank gives
    SEEN.clear();
    json(post("/api/banking/links").contentType(MediaType.APPLICATION_JSON)
        .content("{\"aspsp\":\"Banca Finta\",\"country\":\"IT\",\"fullHistory\":true}"));
    String state = JSON.readTree(seen("/auth").getLast().body()).get("state").asString();
    mvc.perform(get("/api/banking/callback").param("code", CODE).param("state", state))
        .andExpect(header().string("Location", "/banks?result=ok"));
    bankingService.waitForImports();
    Seen read = seen("/accounts/" + UID + "/transactions").getFirst();
    assertTrue(read.query().contains("strategy=longest"), read.query());
    assertFalse(read.query().contains("date_from"), read.query());
    assertEquals(3, rows("Da_file"), "two rows, one movement: the bank's goes to the first, nothing added");
    assertEquals("Riga finta", jdbc.queryForObject("SELECT description FROM " + SCHEMA
        + ".bank_transaction WHERE external_ref = 'r-old'", String.class));
    JsonNode link = json(get("/api/banking/links")).get(0);
    assertEquals(0, link.get("lastAmbiguous").asInt());
    assertFalse(link.get("createdAt").isNull());
    JsonNode account = link.get("accounts").get(0);
    assertEquals("Da_file", account.get("moneyAccount").asString());
    assertEquals("2026-11-03", account.get("bankFrom").asString());
    assertEquals(1, account.get("extraRows").asInt(), "the double is what a replace would delete");
  }

  @Test
  void moneyAccountsAreListedAndDeletedOnlyWithTheNameTypedAgain() throws Exception {
    String bob = register();
    movements.add(bob, """
        [{"account":"Conto vecchio","datetime":"2026-01-10T00:00:00.000+00:00","description":"Riga 1","amount":-2.00,"currency":"EUR"},
         {"account":"Conto vecchio","datetime":"2026-02-10T00:00:00.000+00:00","description":"Riga 2","amount":5.00,"currency":"EUR"},
         {"account":"Da tenere","datetime":"2026-03-10T00:00:00.000+00:00","description":"Riga 3","amount":1.00,"currency":"EUR"}]""");
    mvc.perform(post("/api/banks/transactions/accounts/Conto vecchio/balance").header("Authorization", bob)
        .contentType(MediaType.APPLICATION_JSON).content("{\"amountToday\":100}")).andExpect(status().isOk());

    String list = mvc.perform(get("/api/money-accounts").header("Authorization", bob)).andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    JsonNode accounts = JSON.readTree(list);
    assertEquals(2, accounts.size());
    JsonNode old = accounts.get(0);
    assertEquals("Conto vecchio", old.get("name").asString());
    assertEquals(2, old.get("rows").asInt());
    assertEquals(2, old.get("manualRows").asInt());
    assertEquals(0, old.get("fileRows").asInt());
    assertEquals(0, old.get("bankRows").asInt());
    assertEquals("2026-01-10", old.get("firstDate").asString());
    assertEquals("2026-02-10", old.get("lastDate").asString());
    assertFalse(old.get("linked").asBoolean());

    for (String confirm : List.of("conto vecchio", "Conto vecchio ", "")) {
      mvc.perform(post("/api/money-accounts/delete").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\":\"Conto vecchio\",\"confirm\":\"" + confirm + "\"}"))
          .andExpect(status().isBadRequest()).andExpect(status().reason("CONFIRMATION_MISMATCH"));
    }
    mvc.perform(post("/api/money-accounts/delete").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Conto vecchio\",\"confirm\":\"Conto vecchio\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(2));
    mvc.perform(get("/api/money-accounts").header("Authorization", bob))
        .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Da tenere"));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + movements.schemaOf(bob)
        + ".daily_balance WHERE account = 'Conto vecchio'", Integer.class));
    mvc.perform(post("/api/money-accounts/delete").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Conto vecchio\",\"confirm\":\"Conto vecchio\"}"))
        .andExpect(status().isNotFound()).andExpect(status().reason("ACCOUNT_NOT_FOUND"));
    // somebody else's account with the same name is not there for alice
    mvc.perform(post("/api/money-accounts/delete").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Da tenere\",\"confirm\":\"Da tenere\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/money-accounts")).andExpect(status().isUnauthorized());
  }

  @Test
  void aMoneyAccountWhereABankImportsIsNotDeletedUntilItIsUnlinked() throws Exception {
    PAGES.put("", page(null, tx("l-1", "5.00", "DBIT", "2026-11-02", "Riga finta")));
    long accountId = link();
    associate(accountId, "Collegato");
    mvc.perform(get("/api/money-accounts").header("Authorization", alice))
        .andExpect(jsonPath("$[?(@.name == 'Collegato')].linked").value(true))
        .andExpect(jsonPath("$[?(@.name == 'Collegato')].bankRows").value(1));
    mvc.perform(post("/api/money-accounts/delete").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Collegato\",\"confirm\":\"Collegato\"}"))
        .andExpect(status().isConflict()).andExpect(status().reason("ACCOUNT_LINKED"));
    assertEquals(1, rows("Collegato"));
    long linkId = jdbc.queryForObject("SELECT id FROM " + SCHEMA + ".bank_link", Long.class);
    mvc.perform(delete("/api/banking/links/" + linkId).header("Authorization", alice)).andExpect(status().isNoContent());
    mvc.perform(post("/api/money-accounts/delete").header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Collegato\",\"confirm\":\"Collegato\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.rows").value(1));
    assertEquals(0, rows("Collegato"));
  }
}
