package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import com.rondinella.moneymanageapi.plan.Plans;
import com.rondinella.moneymanageapi.tenant.TenantContext;
import com.rondinella.moneymanageapi.tenant.TenantSchemas;
import com.rondinella.moneymanageapi.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Plans: versions, inputs from money, projection, planned against real. Made-up data only. */
@SpringBootTest(properties = "app.auth.max-registrations=10000")
@AutoConfigureMockMvc
class PlansTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired
  MockMvc mvc;
  @Autowired
  JdbcTemplate jdbc;
  @Autowired
  TenantSchemas tenantSchemas;
  @Autowired
  Plans plans;
  @Autowired
  UserRepository users;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  private String username;

  private String register(boolean sampleData) throws Exception {
    username = "pl" + UUID.randomUUID().toString().substring(0, 8);
    String body = "{\"username\":\"" + username + "\",\"password\":\"password123\",\"sampleData\":" + sampleData + "}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return "Bearer " + JSON.readTree(json).get("token").asString();
  }

  private String schema() {
    return TenantContext.schemaOf(users.findByUsername(username).orElseThrow().getId());
  }

  private JsonNode read(String auth, String path) throws Exception {
    return JSON.readTree(mvc.perform(get(path)
        .header("Authorization", auth)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private JsonNode send(MockHttpServletRequestBuilder request, String auth, String body, int status) throws Exception {
    String json = mvc.perform(request.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    return json.isEmpty() ? null : JSON.readTree(json);
  }

  private void refused(MockHttpServletRequestBuilder request, String auth, String body, String code) throws Exception {
    mvc.perform(request.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest()).andExpect(status().reason(code));
  }

  private static void same(String expected, JsonNode actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " != " + actual);
  }

  private static LocalDate today() {
    return LocalDate.now(AccountPages.ZONE);
  }

  /** 10 000 own, put in 10 000, nothing a month, two years from today, the default rates */
  private static String config(String target, String extra) {
    return "{\"targetDate\":\"" + target + "\",\"startValue\":10000,\"startValueOwn\":true,\"invested\":10000,"
        + "\"investedOwn\":true,\"monthly\":0,\"monthlyOwn\":true" + extra + "}";
  }

  private static String plan(String name, String config) {
    return "{\"name\":" + (name == null ? "null" : "\"" + name + "\"") + ",\"config\":" + config + "}";
  }

  @Test
  void theSampleDataHaveAPlanWithTwoVersionsAndPlannedAgainstReal() throws Exception {
    String auth = register(true);
    JsonNode overview = read(auth, "/api/plans");
    assertEquals(1, overview.get("plans").size());
    JsonNode summary = overview.get("plans").get(0);
    assertEquals("Indipendenza (esempio)", summary.get("name").asString());
    assertEquals(2, summary.get("versions").asInt());
    JsonNode tracking = summary.get("tracking");
    assertEquals(2, tracking.get("version").asInt());
    assertNotEquals("WAITING", tracking.get("status").asString());
    // saved five months ago: that month and the four after it are complete
    assertEquals(5, tracking.get("points").size());
    assertEquals(YearMonth.from(today()).minusMonths(1).toString(), tracking.get("month").asString());
    // the money inputs: the sample investments, about 400 a month
    JsonNode money = overview.get("money");
    assertTrue(money.get("value").decimalValue().signum() > 0);
    assertEquals(12, money.get("monthlyMonths").asInt());
    assertTrue(money.get("monthly").decimalValue().compareTo(new BigDecimal("200")) > 0);
    assertFalse(money.get("historicalRate").isNull());

    // every month from the first version on is kept, once: running the monthly job again adds nothing
    String schema = schema();
    long id = summary.get("id").asLong();
    assertEquals(12, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".plan_snapshot WHERE plan_id = ?",
        Integer.class, id));
    assertEquals(0, TenantContext.runAs(schema, () -> {
      plans.monthlySnapshots();
      return jdbc.queryForObject("SELECT count(*) - 12 FROM " + schema + ".plan_snapshot", Integer.class);
    }));

    JsonNode plan = read(auth, "/api/plans/" + id);
    assertTrue(plan.get("latest").asBoolean());
    assertEquals(2, plan.get("version").get("number").asInt());
    assertEquals(2, plan.get("version").get("config").get("events").size());
    assertEquals(2, plan.get("versions").size());
    JsonNode projection = plan.get("projection");
    assertEquals(3, projection.get("scenarios").size());
    assertEquals(projection.get("months").asInt() + 1, projection.get("labels").size());
    assertEquals(projection.get("labels").size(), projection.get("base").size());
    // the version in use takes today's money value as its start (it is not the user's own)
    assertFalse(plan.get("version").get("config").get("startValueOwn").asBoolean());
    same(money.get("value").decimalValue().toPlainString(), projection.get("base").get(0));

    JsonNode first = read(auth, "/api/plans/" + id + "?version=1");
    assertFalse(first.get("latest").asBoolean());
    same("300", first.get("version").get("config").get("monthly"));
    assertEquals(12, first.get("tracking").get("points").size());
    mvc.perform(get("/api/plans/" + id + "?version=9").header("Authorization", auth)).andExpect(status().isNotFound());

    // Monte Carlo from three years of monthly returns
    JsonNode mc = send(post("/api/plans/projection"), auth, "{\"config\":" + config(today().plusYears(10).toString(),
        ",\"monthly\":400") + ",\"monteCarlo\":true}", 200).get("monteCarlo");
    assertTrue(mc.get("available").asBoolean());
    assertTrue(mc.get("historyMonths").asInt() >= 30);
    assertEquals(121, mc.get("p50").size());
    assertTrue(mc.get("p5").get(120).decimalValue().compareTo(mc.get("p95").get(120).decimalValue()) < 0);
  }

  @Test
  void aPlanIsSavedInVersionsAndTheNumbersAreTheOnesWorkedOutByHand() throws Exception {
    String auth = register(false);
    JsonNode empty = read(auth, "/api/plans");
    assertEquals(0, empty.get("plans").size());
    same("0", empty.get("money").get("value"));
    assertEquals(0, empty.get("money").get("monthlyMonths").asInt());

    String target = today().plusYears(2).toString();
    JsonNode created = send(post("/api/plans"), auth, plan("  Casa   al mare ", config(target, "")), 201);
    long id = created.get("id").asLong();
    assertEquals("Casa al mare", created.get("name").asString());
    assertEquals(1, created.get("version").get("number").asInt());
    JsonNode p = created.get("projection");
    assertEquals(24, p.get("months").asInt());
    assertEquals(2.0, p.get("years").asDouble(), 1e-12);
    JsonNode base = p.get("scenarios").get(1);
    assertEquals("BASE", base.get("key").asString());
    // 10 000 x 1.05^2 = 11 025; tax 26% of 1 025 = 266.50; 10 758.50 / 1.02^2 = 10 340.73
    same("11025.00", base.get("gross"));
    same("266.50", base.get("tax"));
    same("10758.50", base.get("afterTax"));
    same("10340.73", base.get("real"));
    same("10609.00", p.get("scenarios").get(0).get("gross"));
    same("11449.00", p.get("scenarios").get(2).get("gross"));
    assertEquals("WAITING", created.get("tracking").get("status").asString());
    assertEquals(YearMonth.from(today()).plusMonths(1).atDay(1).toString(),
        created.get("tracking").get("nextCheck").asString());

    // saving again: version 2, version 1 stays as it was
    JsonNode saved = send(put("/api/plans/" + id), auth, plan("Casa al mare", config(target,
        ",\"monthly\":250,\"rateBase\":4.5,\"events\":[{\"kind\":\"ONE_OFF\",\"date\":\"" + today().plusMonths(3)
            + "\",\"amount\":-2000,\"label\":\" Auto \"},{\"kind\":\"monthly\",\"date\":\"" + today().plusYears(1)
            + "\",\"amount\":100}]")), 200);
    assertEquals(2, saved.get("version").get("number").asInt());
    assertEquals(2, saved.get("versions").size());
    assertEquals("Auto", saved.get("version").get("config").get("events").get(0).get("label").asString());
    assertEquals("MONTHLY", saved.get("version").get("config").get("events").get(1).get("kind").asString());
    same("4.5", saved.get("version").get("config").get("rateBase"));
    JsonNode v1 = read(auth, "/api/plans/" + id + "?version=1");
    same("0", v1.get("version").get("config").get("monthly"));
    same("11025.00", v1.get("projection").get("scenarios").get(1).get("gross"));

    // the projection of values not saved, without Monte Carlo history: the band is not there, and says why
    JsonNode projection = send(post("/api/plans/projection"), auth, "{\"config\":" + config(target, "")
        + ",\"monteCarlo\":true}", 200);
    same("11025.00", projection.get("scenarios").get(1).get("gross"));
    assertFalse(projection.get("monteCarlo").get("available").asBoolean());
    assertEquals("HISTORY_TOO_SHORT", projection.get("monteCarlo").get("reason").asString());
    assertTrue(send(post("/api/plans/projection"), auth, "{\"config\":" + config(target, "") + "}", 200)
        .get("monteCarlo").isNull());

    // "duplicate as a scenario": a new plan from the same values
    send(post("/api/plans"), auth, plan("Casa al mare, prudente", config(target, ",\"rateBase\":3")), 201);
    JsonNode two = read(auth, "/api/plans");
    assertEquals(2, two.get("plans").size());
    assertEquals("Casa al mare, prudente", two.get("plans").get(0).get("name").asString());

    // delete: versions, events and snapshots go with it
    mvc.perform(delete("/api/plans/" + id).header("Authorization", auth)).andExpect(status().isNoContent());
    mvc.perform(get("/api/plans/" + id).header("Authorization", auth)).andExpect(status().isNotFound());
    mvc.perform(delete("/api/plans/" + id).header("Authorization", auth)).andExpect(status().isNotFound());
    assertEquals(1, read(auth, "/api/plans").get("plans").size());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema() + ".plan_event", Integer.class));
  }

  @Test
  void theInputsFromMoneyAreTakenOnSavingAndTheUserCanUseTheirOwn() throws Exception {
    String auth = register(true);
    JsonNode money = read(auth, "/api/plans").get("money");
    String target = today().plusYears(5).toString();
    // the values given for the inputs from money are ignored on saving: money's are taken
    String fromMoney = "{\"targetDate\":\"" + target + "\",\"startValue\":1,\"startValueOwn\":false,\"invested\":1,"
        + "\"investedOwn\":false,\"monthly\":1,\"monthlyOwn\":false}";
    JsonNode created = send(post("/api/plans"), auth, plan("Da money", fromMoney), 201);
    JsonNode config = created.get("version").get("config");
    same(money.get("value").decimalValue().toPlainString(), config.get("startValue"));
    same(money.get("invested").decimalValue().toPlainString(), config.get("invested"));
    same(money.get("monthly").decimalValue().toPlainString(), config.get("monthly"));
    same(money.get("value").decimalValue().toPlainString(), created.get("version").get("moneyValue"));
    // the user's own: kept as given
    JsonNode own = send(put("/api/plans/" + created.get("id").asLong()), auth, plan("Da money", config(target, "")), 200);
    same("10000", own.get("version").get("config").get("startValue"));
    assertTrue(own.get("version").get("config").get("startValueOwn").asBoolean());
    same(money.get("value").decimalValue().toPlainString(), own.get("version").get("moneyValue"));
  }

  @Test
  void whatIsRefusedHasACode() throws Exception {
    String auth = register(false);
    String target = today().plusYears(2).toString();
    refused(post("/api/plans"), auth, plan("  ", config(target, "")), "NAME_REQUIRED");
    refused(post("/api/plans"), auth, plan("x".repeat(61), config(target, "")), "NAME_TOO_LONG");
    refused(post("/api/plans"), auth, plan("P", config(today().toString(), "")), "TARGET_DATE");
    refused(post("/api/plans"), auth, plan("P", config(today().plusYears(101).toString(), "")), "TARGET_DATE");
    refused(post("/api/plans"), auth, "{\"name\":\"P\"}", "TARGET_DATE");
    refused(post("/api/plans"), auth, plan("P", config(target, "").replace("\"monthly\":0", "\"monthly\":-1")),
        "MONTHLY_RANGE");
    refused(post("/api/plans"), auth, plan("P", config(target, "").replace("\"startValue\":10000",
        "\"startValue\":1.234")), "START_RANGE");
    refused(post("/api/plans"), auth, plan("P", config(target, ",\"rateBase\":31")), "RATE_RANGE");
    refused(post("/api/plans"), auth, plan("P", config(target, ",\"rateBase\":8")), "RATE_ORDER");
    refused(post("/api/plans"), auth, plan("P", config(target, ",\"inflation\":21")), "INFLATION_RANGE");
    refused(post("/api/plans"), auth, plan("P", config(target, ",\"taxRate\":-1")), "TAX_RANGE");
    refused(post("/api/plans"), auth, plan("P", config(target, ",\"events\":[{\"kind\":\"X\",\"date\":\""
        + today().plusMonths(2) + "\",\"amount\":1}]")), "EVENT_KIND");
    refused(post("/api/plans"), auth, plan("P", config(target, ",\"events\":[{\"kind\":\"ONE_OFF\",\"date\":\""
        + today() + "\",\"amount\":1}]")), "EVENT_DATE");
    refused(post("/api/plans"), auth, plan("P", config(target, ",\"events\":[{\"kind\":\"ONE_OFF\",\"date\":\""
        + today().plusYears(3) + "\",\"amount\":1}]")), "EVENT_DATE");
    refused(post("/api/plans"), auth, plan("P", config(target, ",\"events\":[{\"kind\":\"ONE_OFF\",\"date\":\""
        + today().plusMonths(2) + "\",\"amount\":0}]")), "EVENT_AMOUNT");
    refused(post("/api/plans"), auth, plan("P", config(target, ",\"events\":[{\"kind\":\"ONE_OFF\",\"date\":\""
        + today().plusMonths(2) + "\",\"amount\":5,\"label\":\"" + "y".repeat(61) + "\"}]")), "EVENT_LABEL");
    String many = ",\"events\":[" + ("{\"kind\":\"ONE_OFF\",\"date\":\"" + today().plusMonths(2) + "\",\"amount\":5},")
        .repeat(51);
    refused(post("/api/plans"), auth, plan("P", config(target, many.substring(0, many.length() - 1) + "]")),
        "TOO_MANY_EVENTS");
    mvc.perform(post("/api/plans").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
        .content("{not json")).andExpect(status().isBadRequest());
    mvc.perform(put("/api/plans/12345").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
        .content(plan("P", config(target, "")))).andExpect(status().isNotFound());
    // the projection lets a passed event go (it does not count) but not a passed target
    send(post("/api/plans/projection"), auth, "{\"config\":" + config(target, ",\"events\":[{\"kind\":\"ONE_OFF\","
        + "\"date\":\"" + today().minusYears(1) + "\",\"amount\":-5000}]") + "}", 200);
    refused(post("/api/plans/projection"), auth, "{\"config\":" + config(today().toString(), "") + "}", "TARGET_DATE");
    assertEquals(0, read(auth, "/api/plans").get("plans").size());
  }

  @Test
  void anotherUserNeitherSeesNorTouchesAPlan() throws Exception {
    String alice = register(true);
    long id = read(alice, "/api/plans").get("plans").get(0).get("id").asLong();
    String bob = register(false);
    assertEquals(0, read(bob, "/api/plans").get("plans").size());
    mvc.perform(get("/api/plans/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
    mvc.perform(put("/api/plans/" + id).header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
        .content(plan("Mine", config(today().plusYears(2).toString(), "")))).andExpect(status().isNotFound());
    mvc.perform(delete("/api/plans/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
    assertEquals(2, read(alice, "/api/plans/" + id).get("versions").size());
    mvc.perform(get("/api/plans")).andExpect(status().isUnauthorized());
  }

  @Test
  void theMigrationAddsTheTablesAndChangesNothingElse() {
    String schema = "mig" + UUID.randomUUID().toString().substring(0, 8);
    org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema)
        .locations("classpath:db/tenant").target("8").load().migrate();
    jdbc.update("INSERT INTO " + schema + ".bank_transaction (account, datetime, description, amount, fee, currency, "
        + "cumulative_amount, source, external_ref) VALUES ('A', '2025-01-01 10:00', 'x', -1, 0, 'EUR', 10, 'FILE', null)");
    jdbc.update("INSERT INTO " + schema + ".broker_transaction (datetime, description, isin, quantity, stock_price) "
        + "VALUES ('2025-01-01 10:00', 'ETF', 'XX0000000009', 2, 100)");
    String before = jdbc.queryForList("SELECT * FROM " + schema + ".bank_transaction ORDER BY id").toString()
        + jdbc.queryForList("SELECT * FROM " + schema + ".broker_transaction ORDER BY id");

    tenantSchemas.migrate(schema);
    String after = jdbc.queryForList("SELECT * FROM " + schema + ".bank_transaction ORDER BY id").toString()
        + jdbc.queryForList("SELECT * FROM " + schema + ".broker_transaction ORDER BY id");
    assertEquals(before, after);
    for (String table : new String[]{"plan", "plan_version", "plan_event", "plan_snapshot"}) {
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + "." + table, Integer.class));
    }
    jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
  }
}
