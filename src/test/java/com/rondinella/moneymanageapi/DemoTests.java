package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.auth.AuthService;
import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.demo.DemoAccounts;
import com.rondinella.moneymanageapi.demo.DemoSchemas;
import com.rondinella.moneymanageapi.demo.DemoSettings;
import com.rondinella.moneymanageapi.demo.SampleData;
import com.rondinella.moneymanageapi.stocks.AlphaVantage;
import com.rondinella.moneymanageapi.stocks.SymbolResolver;
import com.rondinella.moneymanageapi.stocks.YahooPrices;
import com.rondinella.moneymanageapi.tenant.TenantMigrations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * "Prova la demo": a sandbox per visitor with the sample data, its limits, what it cannot do, its end and the hourly
 * cleanup, which must never touch another account. Every sandbox here comes from an address of its own (X-Real-IP,
 * as swag sets it), so the limit per address only shows where it is tested. Made-up data only.
 */
@SpringBootTest(properties = {"app.demo.enabled=true", "app.demo.per-ip=3", "app.demo.max-active=100000",
    "app.demo.max-upload-kb=1", "app.demo.cleanup-cron=-", "app.auth.max-registrations=10000"})
@AutoConfigureMockMvc
class DemoTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AtomicInteger ADDRESSES = new AtomicInteger(1);
  private static final DateTimeFormatter DEGIRO = DateTimeFormatter.ofPattern("dd-MM-yyyy");
  private static final String DEGIRO_HEADER = "Data,Ora,Prodotto,ISIN,Quantità,Quotazione,,Commissioni di\n";

  @Autowired
  MockMvc mvc;
  @Autowired
  UserRepository users;
  @Autowired
  JdbcTemplate jdbc;
  @Autowired
  DemoAccounts demos;
  @Autowired
  AuthService authService;
  @Autowired
  SampleData sampleData;
  @Autowired
  TenantMigrations migrations;
  @Autowired
  DemoSchemas demoSchemas;
  @Autowired
  PlatformTransactionManager transactions;
  /** no market in the tests: and a sandbox must never ask it */
  @MockitoBean
  AlphaVantage alphaVantage;
  @MockitoBean
  YahooPrices yahoo;
  @MockitoBean
  SymbolResolver resolver;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  @BeforeEach
  void noMarket() throws IOException {
    when(alphaVantage.enabled()).thenReturn(false);
    when(yahoo.weekly(anyString())).thenThrow(new IOException("no market in the tests"));
  }

  /** an address nobody else in these tests uses (TEST-NET-2, never routed) */
  private static String newAddress() {
    int n = ADDRESSES.getAndIncrement();
    return "198.51." + (n / 250) + "." + (n % 250 + 1);
  }

  private MvcResult createFrom(String address) throws Exception {
    return mvc.perform(post("/api/demo").header("X-Real-IP", address)).andReturn();
  }

  /** a new sandbox: "Bearer token" */
  private String sandbox() throws Exception {
    MvcResult r = createFrom(newAddress());
    assertEquals(201, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return "Bearer " + json(r).get("token").asString();
  }

  private String register() throws Exception {
    String body = "{\"username\":\"dm" + UUID.randomUUID().toString().substring(0, 8)
        + "\",\"password\":\"password123\",\"sampleData\":false}";
    MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
    assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    return "Bearer " + json(r).get("token").asString();
  }

  private static JsonNode json(MvcResult r) throws Exception {
    return JSON.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private MvcResult as(String auth, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
    return mvc.perform(request.header("Authorization", auth)).andReturn();
  }

  private MvcResult as(String auth, AbstractMockHttpServletRequestBuilder<?> request, String jsonBody) throws Exception {
    return as(auth, request.contentType(MediaType.APPLICATION_JSON).content(jsonBody));
  }

  private User userOf(String auth) throws Exception {
    String username = json(as(auth, get("/api/account"))).get("username").asString();
    return users.findByUsername(username).orElseThrow();
  }

  private boolean schemaExists(String schema) {
    return jdbc.queryForObject("select count(*) from information_schema.schemata where schema_name = ?",
        Integer.class, schema) == 1;
  }

  private static String degiro(String isin) {
    return DEGIRO_HEADER + LocalDate.now().minusDays(20).format(DEGIRO) + ",10:00,ETF VERO," + isin + ",2,50,EUR,-1\n";
  }

  @Test
  void oneClickGivesASandboxWithTheSampleData() throws Exception {
    JsonNode providers = json(mvc.perform(get("/api/auth/providers")).andReturn());
    assertTrue(providers.get("demo").asBoolean());

    // the database keeps microseconds: the moment before, a millisecond early
    Instant before = Instant.now().minusMillis(1);
    String auth = sandbox();
    JsonNode account = json(as(auth, get("/api/account")));
    assertTrue(account.get("username").asString().matches("demo-[a-z2-9]{10}"), account.toString());
    assertTrue(account.get("demo").asBoolean());
    assertTrue(account.get("email").isNull());
    assertFalse(account.get("banking").asBoolean());
    assertEquals(24, account.get("demoHours").asInt());
    assertEquals(1, account.get("demoMaxUploadKb").asInt());
    Instant expires = Instant.parse(account.get("expiresAt").asString());
    assertFalse(expires.isBefore(before.plus(Duration.ofHours(24))), "ends 24 hours after it was made");
    assertTrue(expires.isBefore(Instant.now().plus(Duration.ofHours(24)).plusSeconds(1)));

    User user = userOf(auth);
    assertTrue(user.isDemo());
    assertNull(user.getEmail());
    assertTrue(schemaExists("u" + user.getId()));

    // the sample data: two bank accounts, two made-up funds, the categories, a plan
    assertEquals(2, json(as(auth, get("/api/money-accounts"))).size());
    JsonNode investments = json(as(auth, get("/api/investments")));
    assertEquals(200, as(auth, get("/api/investments")).getResponse().getStatus());
    assertTrue(investments.toString().contains(SampleData.ETF_ISIN), "the sample funds are there");
    assertFalse(json(as(auth, get("/api/categories"))).toString().isEmpty());
    assertEquals(200, as(auth, get("/api/net-worth")).getResponse().getStatus());
    assertEquals(200, as(auth, get("/api/fixed-costs")).getResponse().getStatus());
    assertEquals(200, as(auth, get("/api/plans")).getResponse().getStatus());
    assertEquals(200, as(auth, get("/api/assets")).getResponse().getStatus());

    // no password anybody knows: the username alone does not log in
    MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + user.getUsername() + "\",\"password\":\"password123\"}")).andReturn();
    assertEquals(401, login.getResponse().getStatus());
  }

  @Test
  void aSandboxWritesInsideItselfOnly() throws Exception {
    String a = sandbox();
    String b = sandbox();
    String real = register();
    assertEquals(201, as(a, post("/api/categories"), "{\"name\":\"Solo della sandbox A\",\"fixed\":false}")
        .getResponse().getStatus());
    assertEquals(201, as(real, post("/api/categories"), "{\"name\":\"Account vero\",\"fixed\":false}")
        .getResponse().getStatus());
    assertEquals(201, as(a, post("/api/assets/others"),
        "{\"name\":\"Quadro (A)\",\"kind\":\"OTHER\",\"value\":100,\"currency\":\"EUR\"}").getResponse().getStatus());

    String inA = json(as(a, get("/api/categories"))).toString();
    String inB = json(as(b, get("/api/categories"))).toString();
    String inReal = json(as(real, get("/api/categories"))).toString();
    assertTrue(inA.contains("Solo della sandbox A"));
    assertFalse(inB.contains("Solo della sandbox A"), "another sandbox does not see it");
    assertFalse(inReal.contains("Solo della sandbox A"), "a real account does not see it");
    assertFalse(inA.contains("Account vero"), "a sandbox does not see a real account");
    assertFalse(inB.contains("Account vero"));
    assertFalse(json(as(b, get("/api/assets"))).toString().contains("Quadro (A)"));
    // the real account started empty and stays so: none of the sandboxes' accounts
    assertEquals(0, json(as(real, get("/api/money-accounts"))).size());
  }

  @Test
  void threeSandboxesAnHourFromOneAddress() throws Exception {
    String address = newAddress();
    for (int i = 0; i < 3; i++) {
      assertEquals(201, createFrom(address).getResponse().getStatus());
    }
    MvcResult fourth = createFrom(address);
    assertEquals(429, fourth.getResponse().getStatus());
    assertEquals("DEMO_LIMIT_IP", json(fourth).get("code").asString());
    assertEquals("DEMO_LIMIT_IP", json(fourth).get("message").asString());
    assertNotNull(fourth.getResponse().getHeader("Retry-After"));
    // another address is not affected
    assertEquals(201, createFrom(newAddress()).getResponse().getStatus());
  }

  @Test
  void noMoreThanTheSandboxesAllowedOpenAtOnce() {
    // the same service with a cap one above the sandboxes open now: one more, then DEMO_FULL for everybody
    long open = users.countByDemoTrueAndExpiresAtAfter(Instant.now());
    DemoAccounts capped = new DemoAccounts(new DemoSettings(true, 100, (int) open + 1, 24, 256), authService, users,
        sampleData, jdbc, migrations, demoSchemas, transactions);
    assertNotNull(capped.create(newAddress()));
    DemoAccounts.Refused refused = assertThrows(DemoAccounts.Refused.class, () -> capped.create(newAddress()));
    assertEquals("DEMO_FULL", refused.code());
    // a sandbox past its end does not take a place
    jdbc.update("UPDATE public.users SET expires_at = now() - interval '1 minute' WHERE demo AND expires_at > now()");
    assertNotNull(capped.create(newAddress()));
  }

  @Test
  void whatGoesOutsideIsRefusedWithACode() throws Exception {
    String demo = sandbox();
    List<String> wrong = new ArrayList<>();
    expect(wrong, as(demo, get("/api/banking/links")), 403, "DEMO_NO_BANKING");
    expect(wrong, as(demo, get("/api/banking/aspsps")), 403, "DEMO_NO_BANKING");
    expect(wrong, as(demo, post("/api/banking/links"), "{\"aspsp\":\"Banca\",\"country\":\"IT\"}"), 403, "DEMO_NO_BANKING");
    expect(wrong, as(demo, post("/api/banking/sync")), 403, "DEMO_NO_BANKING");
    expect(wrong, as(demo, post("/api/account/email"), "{\"email\":\"chiunque@example.invalid\"}"), 403, "DEMO_NO_EMAIL");
    expect(wrong, as(demo, post("/api/account/oidc-link")), 403, "DEMO_NO_SSO");
    expect(wrong, as(demo, delete("/api/account/oidc-link")), 403, "DEMO_NO_SSO");
    expect(wrong, as(demo, get("/api/instruments/preview").param("symbol", "VWCE.DEX")), 403, "DEMO_NO_PRICES");
    expect(wrong, as(demo, put("/api/instruments/{isin}/symbol", SampleData.ETF_ISIN), "{\"symbol\":\"VWCE.DEX\"}"),
        403, "DEMO_NO_PRICES");
    // files over the limit (1 KB in these tests): bank, broker and prices
    String big = "x".repeat(2048);
    expect(wrong, as(demo, post("/api/brokers/transactions/upload").contentType("text/csv").content(big)), 413,
        "DEMO_UPLOAD_TOO_LARGE");
    expect(wrong, as(demo, post("/api/instruments/{isin}/prices", SampleData.ETF_ISIN).contentType("text/csv")
        .content(big)), 413, "DEMO_UPLOAD_TOO_LARGE");
    expect(wrong, as(demo, multipart("/api/banks/transactions/{bank}/upload", "Revolut")
        .file("multipartFile", big.getBytes(StandardCharsets.UTF_8))), 413, "DEMO_UPLOAD_TOO_LARGE");
    assertTrue(wrong.isEmpty(), "not refused as expected: " + wrong);

    MvcResult tooLarge = as(demo, post("/api/brokers/transactions/upload").contentType("text/csv").content(big));
    assertEquals(1, json(tooLarge).get("limitKb").asInt());
    // a small file goes in
    assertEquals(201, as(demo, post("/api/brokers/transactions/upload").contentType("text/csv;charset=UTF-8")
        .content(degiro("IE00DEMO0001").getBytes(StandardCharsets.UTF_8))).getResponse().getStatus());
    // the internal writes work: a category, a plan's projection, an asset
    assertEquals(201, as(demo, post("/api/categories"), "{\"name\":\"Nuova\",\"fixed\":true}").getResponse().getStatus());
  }

  private static void expect(List<String> wrong, MvcResult r, int status, String code) throws Exception {
    String what = r.getRequest().getMethod() + " " + r.getRequest().getRequestURI();
    String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    if (r.getResponse().getStatus() != status || !body.contains("\"code\":\"" + code + "\"")) {
      wrong.add(what + " -> " + r.getResponse().getStatus() + " " + body);
    }
  }

  @Test
  void aRealAccountIsNotStoppedByTheDemoRules() throws Exception {
    String real = register();
    // open banking off for this user, as before: 404, no demo code
    MvcResult banking = as(real, get("/api/banking/links"));
    assertEquals(404, banking.getResponse().getStatus());
    String big = "x".repeat(2048);
    MvcResult upload = as(real, post("/api/brokers/transactions/upload").contentType("text/csv").content(big));
    assertFalse(upload.getResponse().getContentAsString().contains("DEMO_"), "no demo limit for a real account");
    JsonNode account = json(as(real, get("/api/account")));
    assertFalse(account.get("demo").asBoolean());
    assertFalse(account.has("expiresAt"));
  }

  @Test
  void aSandboxNeverAsksTheMarket() throws Exception {
    when(alphaVantage.enabled()).thenReturn(true);
    when(resolver.resolve(anyString())).thenThrow(new IOException("no market in the tests"));
    String demo = sandbox();
    String real = register();
    // the same kind of fund in both: an ISIN no market has a price of yet
    String demoIsin = "IE00" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    String realIsin = "IE00" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    assertEquals(201, as(demo, post("/api/brokers/transactions/upload").contentType("text/csv;charset=UTF-8")
        .content(degiro(demoIsin).getBytes(StandardCharsets.UTF_8))).getResponse().getStatus());
    assertEquals(200, as(demo, get("/api/investments")).getResponse().getStatus());
    assertEquals(201, as(real, post("/api/brokers/transactions/upload").contentType("text/csv;charset=UTF-8")
        .content(degiro(realIsin).getBytes(StandardCharsets.UTF_8))).getResponse().getStatus());
    assertEquals(200, as(real, get("/api/investments")).getResponse().getStatus());
    assertEquals(200, as(demo, get("/api/net-worth")).getResponse().getStatus());
    // the real account asks (so the mocks would have seen the sandbox too), the sandbox never
    verify(resolver, timeout(5000).atLeastOnce()).resolve(realIsin);
    verify(resolver, never()).resolve(demoIsin);
    verify(yahoo, never()).weekly(demoIsin);
    verify(alphaVantage, never()).weekly(anyString(), eq(demoIsin), any());
  }

  @Test
  void aSandboxEndsAndTheCleanupRemovesItWithItsSchema() throws Exception {
    String auth = sandbox();
    User user = userOf(auth);
    String schema = "u" + user.getId();
    assertEquals(200, as(auth, get("/api/account")).getResponse().getStatus());

    jdbc.update("UPDATE public.users SET expires_at = now() - interval '1 minute' WHERE id = ?", user.getId());
    assertEquals(401, as(auth, get("/api/account")).getResponse().getStatus(), "past its end the token stops working");
    assertEquals(401, as(auth, get("/api/money-accounts")).getResponse().getStatus());

    demos.removeExpired();
    assertTrue(users.findById(user.getId()).isEmpty(), "the account is gone");
    assertFalse(schemaExists(schema), "its schema is gone");
    assertEquals(401, as(auth, get("/api/account")).getResponse().getStatus());
  }

  @Test
  void theCleanupNeverTouchesAnotherAccount() throws Exception {
    // a real account, one whose name happens to start with demo- (from before the name was kept), both with an old
    // expires_at by mistake, and a sandbox still open: only a sandbox past its end goes
    String real = register();
    User realUser = userOf(real);
    jdbc.update("UPDATE public.users SET expires_at = now() - interval '2 days' WHERE id = ?", realUser.getId());

    User lookalike = new User();
    lookalike.setUsername("demo-" + UUID.randomUUID().toString().substring(0, 8));
    lookalike.setPassword("x");
    lookalike.setRole("USER");
    lookalike.setExpiresAt(Instant.now().minus(Duration.ofDays(2)));
    lookalike = users.save(lookalike);
    migrations.migrate("u" + lookalike.getId());

    String open = sandbox();
    User openUser = userOf(open);
    String expired = sandbox();
    User expiredUser = userOf(expired);
    jdbc.update("UPDATE public.users SET expires_at = now() - interval '1 minute' WHERE id = ?", expiredUser.getId());

    demos.removeExpired();

    assertTrue(users.findById(realUser.getId()).isPresent(), "a real account stays");
    assertTrue(schemaExists("u" + realUser.getId()));
    assertTrue(users.findById(lookalike.getId()).isPresent(), "demo- without the flag stays");
    assertTrue(schemaExists("u" + lookalike.getId()));
    assertTrue(users.findById(openUser.getId()).isPresent(), "a sandbox still open stays");
    assertTrue(schemaExists("u" + openUser.getId()));
    assertTrue(users.findById(expiredUser.getId()).isEmpty());
    assertFalse(schemaExists("u" + expiredUser.getId()));
    assertEquals(200, as(real, get("/api/account")).getResponse().getStatus());

    // and the database refuses a demo row without the prefix (or without an end): the cleanup cannot be pointed at one
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE public.users SET demo = true WHERE id = ?", realUser.getId()));
    assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
        "UPDATE public.users SET expires_at = null WHERE id = ?", openUser.getId()));
  }

  @Test
  void nobodyElseGetsADemoName() throws Exception {
    MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"Demo-mio\",\"password\":\"password123\"}")).andReturn();
    assertEquals(400, r.getResponse().getStatus());
    assertTrue(authService.freeUsername("demo-gh", "gh").startsWith("github-"));
  }
}
