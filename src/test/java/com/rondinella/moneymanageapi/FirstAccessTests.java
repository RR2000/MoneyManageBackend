package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import com.rondinella.moneymanageapi.stocks.AlphaVantage;
import com.rondinella.moneymanageapi.stocks.YahooPrices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * The first access and the thin data: a user who has just registered, or has only a little, must see every page
 * answer (empty lists, zeros, "not known"), never an error. Every page of the app reads these endpoints; each case
 * calls all of them and lists the ones that fail. Made-up data only.
 */
@SpringBootTest(properties = "app.auth.max-registrations=10000")
@AutoConfigureMockMvc
@Import(ManualMovements.class)
class FirstAccessTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter DEGIRO = DateTimeFormatter.ofPattern("dd-MM-yyyy");
  private static final String DEGIRO_HEADER = "Data,Ora,Prodotto,ISIN,Quantità,Quotazione,,Commissioni di\n";

  @Autowired
  MockMvc mvc;
  @Autowired
  ManualMovements movements;
  @Autowired
  UserRepository users;
  @Autowired
  PasswordEncoder passwordEncoder;
  @Autowired
  JdbcTemplate jdbc;
  /** no market in the tests */
  @MockitoBean
  AlphaVantage alphaVantage;
  @MockitoBean
  YahooPrices yahoo;

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

  private String register() throws Exception {
    String body = "{\"username\":\"fa" + UUID.randomUUID().toString().substring(0, 8)
        + "\",\"password\":\"password123\",\"sampleData\":false}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn().getResponse().getContentAsString();
    return "Bearer " + JSON.readTree(json).get("token").asString();
  }

  private static LocalDate today() {
    return LocalDate.now(AccountPages.ZONE);
  }

  private static String isin(String country) {
    return country + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
  }

  private static String degiroDay(int daysAgo) {
    return today().minusDays(daysAgo).format(DEGIRO);
  }

  private static String movement(String account, int daysAgo, String description, String amount, String currency) {
    return "{\"account\":\"" + account + "\",\"datetime\":\"" + today().minusDays(daysAgo) + "T10:00:00.000+00:00\","
        + "\"description\":\"" + description + "\",\"amount\":" + amount + ",\"fee\":0,\"currency\":\"" + currency + "\"}";
  }

  private void balance(String auth, String account, String today) throws Exception {
    mvc.perform(post("/api/banks/transactions/accounts/{name}/balance", account).header("Authorization", auth)
        .contentType(MediaType.APPLICATION_JSON).content("{\"amountToday\":" + today + "}"));
  }

  private void trades(String auth, String csvRows) throws Exception {
    MvcResult r = mvc.perform(post("/api/brokers/transactions/upload").header("Authorization", auth)
        .contentType("text/csv;charset=UTF-8").content((DEGIRO_HEADER + csvRows).getBytes(StandardCharsets.UTF_8)))
        .andReturn();
    assertEquals(201, r.getResponse().getStatus(), "upload of the trades");
  }

  /**
   * Calls what the pages of the app call, as the app does, and returns the ones that do not answer 2xx, with the
   * status. Also the page of every account and a plan made from money's own inputs.
   */
  private List<String> sweep(String auth) throws Exception {
    List<String> failed = new ArrayList<>();
    String from = today().minusYears(1).toString();
    String target = today().plusYears(10).toString();
    for (String path : List.of("/api/auth/providers", "/api/account", "/api/banks/transactions/accounts", "/api/money-accounts", "/api/money-accounts/movements/latest",
        "/api/categories", "/api/fixed-costs", "/api/fixed-costs?months=12", "/api/investments",
        "/api/investments/targets", "/api/net-worth", "/api/net-worth?from=" + from, "/api/plans", "/api/realestate",
        "/api/instruments", "/api/assets")) {
      call(failed, get(path), auth, null);
    }
    call(failed, post("/api/money-accounts/movements"), auth, "{}");
    call(failed, post("/api/money-accounts/movements"), auth, "{\"uncategorized\":true}");
    call(failed, post("/api/categories/apply"), auth, "{\"dryRun\":true}");
    // open banking is off for this user: a 404 the app reads as "no banks" (it asks GET /api/account first)
    int banking = mvc.perform(get("/api/banking/links").header("Authorization", auth)).andReturn().getResponse().getStatus();
    if (banking != 404) {
      failed.add("GET /api/banking/links -> " + banking + " (expected 404, feature off)");
    }

    JsonNode accounts = call(failed, get("/api/money-accounts"), auth, null);
    if (accounts != null) {
      for (JsonNode account : accounts) {
        String id = account.get("id").asString();
        call(failed, get("/api/money-accounts/" + id), auth, null);
        call(failed, get("/api/money-accounts/" + id + "/balances"), auth, null);
        call(failed, get("/api/money-accounts/" + id + "/balances?from=" + from), auth, null);
        call(failed, post("/api/money-accounts/" + id + "/movements"), auth, "{}");
      }
    }

    // the plan page: the projection of money's inputs, as the page sends them, with the band, and a plan saved from them
    JsonNode plans = call(failed, get("/api/plans"), auth, null);
    if (plans != null) {
      JsonNode money = plans.get("money");
      String withMoney = "{\"targetDate\":\"" + target + "\",\"startValue\":" + money.get("value") + ",\"startValueOwn\":false,"
          + "\"invested\":" + money.get("invested") + ",\"investedOwn\":false,\"monthly\":" + money.get("monthly")
          + ",\"monthlyOwn\":false}";
      call(failed, post("/api/plans/projection"), auth, "{\"config\":" + withMoney + ",\"monteCarlo\":true}");
      JsonNode created = call(failed, post("/api/plans"), auth, "{\"name\":\"Prova\",\"config\":" + withMoney + "}");
      if (created != null) {
        call(failed, get("/api/plans/" + created.get("id").asLong()), auth, null);
        call(failed, get("/api/plans"), auth, null);
      }
    }
    return failed;
  }

  private JsonNode call(List<String> failed, MockHttpServletRequestBuilder request, String auth, String body)
      throws Exception {
    request.header("Authorization", auth);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    MvcResult r = mvc.perform(request).andReturn();
    int status = r.getResponse().getStatus();
    String what = r.getRequest().getMethod() + " " + r.getRequest().getRequestURI()
        + (r.getRequest().getQueryString() == null ? "" : "?" + r.getRequest().getQueryString());
    if (status / 100 != 2) {
      failed.add(what + " -> " + status);
      return null;
    }
    String json = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    if (json.contains("NaN") || json.contains("Infinity")) {
      failed.add(what + " -> not a number in the answer");
    }
    return json.isEmpty() ? null : JSON.readTree(json);
  }

  private static void allAnswer(List<String> failed) {
    assertTrue(failed.isEmpty(), "endpoints that fail: " + failed);
  }

  @Test
  void aUserWhoHasJustRegisteredSeesEveryPage() throws Exception {
    String auth = register();
    allAnswer(sweep(auth));
  }

  @Test
  void anAccountWhoseSchemaIsNotThereYet() throws Exception {
    // a user row without its schema (a registration whose migration stopped half way, before the fix): the first
    // request creates the schema instead of failing on every page
    String username = "fs" + UUID.randomUUID().toString().substring(0, 8);
    User user = new User();
    user.setUsername(username);
    user.setPassword(passwordEncoder.encode("password123"));
    user.setRole("USER");
    user = users.save(user);
    String schema = "u" + user.getId();
    assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.schemata where schema_name = ?",
        Integer.class, schema));

    String json = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
        .andReturn().getResponse().getContentAsString();
    String auth = "Bearer " + JSON.readTree(json).get("token").asString();
    allAnswer(sweep(auth));
    assertEquals(1, jdbc.queryForObject("select count(*) from information_schema.schemata where schema_name = ?",
        Integer.class, schema));
  }

  @Test
  void oneMovementOnly() throws Exception {
    String auth = register();
    movements.add(auth, "[" + movement("Conto unico", 3, "Primo stipendio", "1500.00", "EUR") + "]");
    balance(auth, "Conto unico", "1500.00");
    allAnswer(sweep(auth));
  }

  @Test
  void movementsWithoutTodaysBalance() throws Exception {
    // a file imported without the balance of today: no cumulative amount, no daily balance
    String auth = register();
    movements.add(auth, "[" + movement("Senza saldo", 10, "Spesa", "-42.10", "EUR") + ","
        + movement("Senza saldo", 2, "Rimborso", "12.00", "EUR") + "]");
    allAnswer(sweep(auth));
  }

  @Test
  void anAccountWhoseMovementsAreAllToday() throws Exception {
    String auth = register();
    movements.add(auth, "[" + movement("Oggi", 0, "Apertura", "100.00", "EUR") + "]");
    balance(auth, "Oggi", "100.00");
    allAnswer(sweep(auth));
  }

  @Test
  void anAccountInAnotherCurrencyOnly() throws Exception {
    String auth = register();
    movements.add(auth, "[" + movement("Dollari", 20, "Deposit", "300.00", "USD") + ","
        + movement("Dollari", 5, "Coffee", "-4.50", "USD") + "]");
    balance(auth, "Dollari", "295.50");
    allAnswer(sweep(auth));
  }

  @Test
  void investmentsWithoutPrices() throws Exception {
    String auth = register();
    String etf = isin("IE");
    trades(auth, degiroDay(30) + ",10:00,ETF SENZA PREZZI," + etf + ",4,100,EUR,-1\n");
    allAnswer(sweep(auth));
  }

  @Test
  void investmentsWithOnePriceOnTheDayOfTheTrade() throws Exception {
    String auth = register();
    String etf = isin("IE");
    trades(auth, degiroDay(0) + ",10:00,ETF DI OGGI," + etf + ",2,50,EUR,0\n");
    mvc.perform(post("/api/instruments/{isin}/prices", etf).header("Authorization", auth).contentType("text/csv")
        .content(today() + ",50\n"));
    allAnswer(sweep(auth));
  }

  @Test
  void everythingSoldAndASaleWithoutAPurchase() throws Exception {
    String auth = register();
    String sold = isin("IE");
    String orphan = isin("LU");
    trades(auth, degiroDay(60) + ",10:00,TUTTO VENDUTO," + sold + ",5,100,EUR,-1\n"
        + degiroDay(30) + ",10:00,TUTTO VENDUTO," + sold + ",-5,110,EUR,-1\n"
        + degiroDay(10) + ",10:00,SOLO VENDITA," + orphan + ",-3,20,EUR,-1\n");
    allAnswer(sweep(auth));
  }

  @Test
  void investmentsInAnotherCurrencyOnly() throws Exception {
    String auth = register();
    String usd = isin("US");
    trades(auth, degiroDay(90) + ",15:30,AZIONE IN DOLLARI," + usd + ",3,150,USD,-1\n");
    mvc.perform(post("/api/instruments/{isin}/prices", usd).header("Authorization", auth).contentType("text/csv")
        .content(today().minusDays(1) + ",170\n"));
    allAnswer(sweep(auth));
  }

  @Test
  void aPlanWithoutInvestments() throws Exception {
    // cash only: money's inputs for the plan are zero, the plan and its band must still be computed
    String auth = register();
    movements.add(auth, "[" + movement("Contanti", 40, "Stipendio", "2000.00", "EUR") + "]");
    balance(auth, "Contanti", "2000.00");
    allAnswer(sweep(auth));
  }

  @Test
  void assetsAndDebtsWithoutMovements() throws Exception {
    String auth = register();
    String other = "{\"name\":\"Bici\",\"kind\":\"VEHICLE\",\"value\":800,\"currency\":\"EUR\"}";
    String debt = "{\"name\":\"Prestito\",\"balance\":1000,\"currency\":\"EUR\"}";
    String otherUsd = "{\"name\":\"Orologio\",\"kind\":\"OTHER\",\"value\":500,\"currency\":\"USD\"}";
    for (String[] r : new String[][]{{"/api/assets/others", other}, {"/api/assets/debts", debt},
        {"/api/assets/others", otherUsd}}) {
      MvcResult created = mvc.perform(post(r[0]).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
          .content(r[1])).andReturn();
      assertEquals(201, created.getResponse().getStatus(), r[0]);
    }
    allAnswer(sweep(auth));
  }
}
