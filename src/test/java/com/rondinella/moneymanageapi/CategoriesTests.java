package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import com.rondinella.moneymanageapi.demo.SampleData;
import com.rondinella.moneymanageapi.tenant.TenantSchemas;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Categories, rules, the category of the movements and the fixed costs. Made-up data only. */
@SpringBootTest(properties = "app.auth.max-registrations=10000")
@AutoConfigureMockMvc
@Import(ManualMovements.class)
class CategoriesTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired
  ManualMovements movements;
  @Autowired
  MockMvc mvc;
  @Autowired
  JdbcTemplate jdbc;
  @Autowired
  TenantSchemas tenantSchemas;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  private String register(boolean sampleData) throws Exception {
    String body = "{\"username\":\"c" + UUID.randomUUID().toString().substring(0, 8)
        + "\",\"password\":\"password123\",\"sampleData\":" + sampleData + "}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return "Bearer " + JSON.readTree(json).get("token").asString();
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

  private void upload(String auth, String json) {
    movements.add(auth, json);
  }

  private static String movement(String account, String day, String description, String amount) {
    return "{\"account\":\"" + account + "\",\"datetime\":\"" + day + "T10:00:00.000+00:00\",\"description\":\""
        + description + "\",\"amount\":" + amount + ",\"currency\":\"EUR\"}";
  }

  private JsonNode movements(String auth, String accountId, String body) throws Exception {
    String path = accountId == null ? "/api/money-accounts/movements" : "/api/money-accounts/" + accountId + "/movements";
    return send(post(path), auth, body, 200);
  }

  private JsonNode find(JsonNode items, String description) {
    for (JsonNode m : items) {
      if (m.get("description").asString().equals(description)) {
        return m;
      }
    }
    return fail("no movement " + description);
  }

  private static LocalDate today() {
    return LocalDate.now(AccountPages.ZONE);
  }

  @Test
  void theSampleDataHaveCategoriesRulesAndFixedCosts() throws Exception {
    String auth = register(true);
    JsonNode overview = read(auth, "/api/categories");
    assertEquals(7, overview.get("categories").size());
    List<String> fixed = new ArrayList<>();
    overview.get("categories").forEach(c -> {
      if (c.get("fixed").asBoolean()) {
        fixed.add(c.get("name").asString());
      }
    });
    assertEquals(List.of("Abbonamenti (esempio)", "Assicurazioni (esempio)", "Bollette (esempio)", "Casa (esempio)"), fixed);
    assertEquals(8, overview.get("rules").size());
    long uncategorized = overview.get("uncategorized").asLong();
    assertTrue(uncategorized > 0, "the small expenses and the interests have no category");

    JsonNode costs = read(auth, "/api/fixed-costs");
    assertEquals("EUR", costs.get("currency").asString());
    assertEquals(12, costs.get("months").asInt());
    assertEquals(12, costs.get("monthsUsed").asInt());
    assertEquals(today().minusMonths(12).withDayOfMonth(1).toString(), costs.get("from").asString());
    assertEquals(today().withDayOfMonth(1).minusDays(1).toString(), costs.get("to").asString());
    assertEquals(uncategorized, costs.get("uncategorized").asLong());
    assertEquals(4, costs.get("categories").size());
    JsonNode casa = costs.get("categories").get(0);
    assertEquals("Casa (esempio)", casa.get("name").asString(), "the rent is the largest");
    same("720", casa.get("monthly"));
    same("8640", casa.get("yearly"));
    JsonNode insurance = null;
    for (JsonNode c : costs.get("categories")) {
      if (c.get("name").asString().startsWith("Assicurazioni")) {
        insurance = c;
      }
    }
    assertNotNull(insurance);
    same("45", insurance.get("monthly"));         // 540 once in the 12 months
    same("540", insurance.get("thisMonth"));      // and again on the 1st of this month
    BigDecimal monthly = costs.get("monthly").decimalValue();
    BigDecimal setAside = costs.get("setAside").decimalValue();
    assertTrue(setAside.compareTo(monthly) >= 0 && setAside.compareTo(monthly.add(BigDecimal.TEN)) < 0, setAside + " / " + monthly);
    assertEquals(0, setAside.remainder(BigDecimal.TEN).signum());
    assertEquals(10, costs.get("roundingStep").asInt());

    JsonNode yearly = null;
    JsonNode phone = null;
    JsonNode gas = null;
    for (JsonNode u : costs.get("upcoming")) {
      String rule = u.get("rule").isNull() ? "" : u.get("rule").asString();
      switch (rule) {
        case "assicurazione" -> yearly = u;
        case "abbonamento" -> phone = u;
        case "bolletta gas" -> gas = u;
        default -> {
        }
      }
    }
    assertNotNull(yearly);
    assertEquals("ANNUAL", yearly.get("cadence").asString());
    assertEquals(today().withDayOfMonth(1).plusYears(1).toString(), yearly.get("expectedOn").asString());
    same("540", yearly.get("amount"));
    assertNotNull(phone);
    assertEquals("MONTHLY", phone.get("cadence").asString());
    same("12.99", phone.get("amount"));
    assertNotNull(gas);
    assertEquals("BIMONTHLY", gas.get("cadence").asString());

    // a movement says its category; the ones without one can be listed alone, for one account or for all
    String checking = AccountPages.idOf(SampleData.CHECKING);
    JsonNode rent = find(movements(auth, checking, "{\"q\":\"affitto\",\"limit\":5}").get("items"), "Affitto (esempio)");
    assertEquals("Casa (esempio)", rent.get("category").asString());
    assertEquals("RULE", rent.get("categorySource").asString());
    assertEquals(checking, rent.get("accountId").asString());
    JsonNode loose = movements(auth, null, "{\"uncategorized\":true,\"limit\":200}");
    assertTrue(loose.get("items").size() > 0);
    for (JsonNode m : loose.get("items")) {
      assertTrue(m.get("categoryId").isNull());
      assertNotNull(m.get("account").asString());
    }
  }

  @Test
  void categoriesAndRulesAreCreatedChangedAndDeletedWithTheirRules() throws Exception {
    String auth = register(false);
    JsonNode empty = read(auth, "/api/categories");
    assertEquals(0, empty.get("categories").size());
    assertEquals(0, empty.get("uncategorized").asLong());

    JsonNode home = send(post("/api/categories"), auth, "{\"name\":\"  Casa   e mutuo \",\"fixed\":true}", 201);
    assertEquals("Casa e mutuo", home.get("name").asString());
    assertTrue(home.get("fixed").asBoolean());
    long homeId = home.get("id").asLong();
    refused(post("/api/categories"), auth, "{\"name\":\"casa E MUTUO\"}", "NAME_TAKEN");
    refused(post("/api/categories"), auth, "{\"name\":\"  \"}", "NAME_REQUIRED");
    refused(post("/api/categories"), auth, "{\"name\":\"" + "x".repeat(61) + "\"}", "NAME_TOO_LONG");
    JsonNode renamed = send(put("/api/categories/{id}", homeId), auth, "{\"name\":\"Casa\",\"fixed\":true}", 200);
    assertEquals("Casa", renamed.get("name").asString());
    send(put("/api/categories/{id}", homeId), auth, "{\"name\":\"CASA\",\"fixed\":true}", 200); // its own name, other case
    mvc.perform(put("/api/categories/{id}", 999999).header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"x\"}")).andExpect(status().isNotFound());

    upload(auth, "[" + movement("Conto_prova", "2026-10-01", "BONIFICO AFFITTO OTTOBRE", "-700") + "]");
    String account = AccountPages.idOf("Conto_prova");
    JsonNode rule = send(post("/api/categories/rules"), auth, "{\"categoryId\":" + homeId
        + ",\"pattern\":\"  Affitto \",\"direction\":\"out\",\"accountId\":\"" + account + "\"}", 201);
    assertEquals("Affitto", rule.get("pattern").asString());
    assertEquals("OUT", rule.get("direction").asString());
    assertEquals(account, rule.get("accountId").asString());
    assertEquals("Conto_prova", rule.get("account").asString());
    long ruleId = rule.get("id").asLong();
    refused(post("/api/categories/rules"), auth, "{\"categoryId\":" + homeId + ",\"pattern\":\"affitto\",\"direction\":\"OUT\",\"accountId\":\""
        + account + "\"}", "RULE_EXISTS");
    refused(post("/api/categories/rules"), auth, "{\"categoryId\":" + homeId + ",\"pattern\":\"a\"}", "PATTERN_TOO_SHORT");
    refused(post("/api/categories/rules"), auth, "{\"categoryId\":" + homeId + ",\"pattern\":\" \"}", "PATTERN_REQUIRED");
    refused(post("/api/categories/rules"), auth, "{\"categoryId\":" + homeId + ",\"pattern\":\"" + "x".repeat(101) + "\"}",
        "PATTERN_TOO_LONG");
    refused(post("/api/categories/rules"), auth, "{\"categoryId\":" + homeId + ",\"pattern\":\"ab\",\"direction\":\"UP\"}",
        "DIRECTION");
    refused(post("/api/categories/rules"), auth, "{\"categoryId\":" + homeId + ",\"pattern\":\"ab\",\"accountId\":\"000000000000\"}",
        "ACCOUNT");
    refused(post("/api/categories/rules"), auth, "{\"categoryId\":999999,\"pattern\":\"ab\"}", "CATEGORY");

    // a new rule does not touch the movements already there: "apply to all" does, after a preview
    JsonNode overview = read(auth, "/api/categories");
    assertEquals(1, overview.get("uncategorized").asLong());
    JsonNode preview = send(post("/api/categories/apply"), auth, "{}", 200);
    assertTrue(preview.get("dryRun").asBoolean(), "a preview unless said otherwise");
    assertEquals(1, preview.get("categorized").asInt());
    assertEquals(1, read(auth, "/api/categories").get("uncategorized").asLong());
    JsonNode applied = send(post("/api/categories/apply"), auth, "{\"dryRun\":false}", 200);
    assertEquals(1, applied.get("categorized").asInt());
    assertEquals(0, read(auth, "/api/categories").get("uncategorized").asLong());
    assertEquals(1, read(auth, "/api/categories").get("categories").get(0).get("movements").asLong());

    // changed so it matches nothing: apply takes the category away again
    send(put("/api/categories/rules/{id}", ruleId), auth, "{\"categoryId\":" + homeId + ",\"pattern\":\"mutuo\"}", 200);
    JsonNode cleared = send(post("/api/categories/apply"), auth, "{\"dryRun\":false}", 200);
    assertEquals(1, cleared.get("cleared").asInt());

    // deleting the category deletes its rules, its movements stay without a category
    send(put("/api/categories/rules/{id}", ruleId), auth, "{\"categoryId\":" + homeId + ",\"pattern\":\"affitto\"}", 200);
    send(post("/api/categories/apply"), auth, "{\"dryRun\":false}", 200);
    send(delete("/api/categories/{id}", homeId), auth, "", 204);
    JsonNode after = read(auth, "/api/categories");
    assertEquals(0, after.get("categories").size());
    assertEquals(0, after.get("rules").size());
    assertEquals(1, after.get("uncategorized").asLong());
    mvc.perform(delete("/api/categories/rules/{id}", ruleId).header("Authorization", auth)).andExpect(status().isNotFound());
  }

  @Test
  void rulesCategorizeNewMovementsAndNeverOverwriteAChoiceByHand() throws Exception {
    String auth = register(false);
    long bills = send(post("/api/categories"), auth, "{\"name\":\"Bollette\",\"fixed\":true}", 201).get("id").asLong();
    long energy = send(post("/api/categories"), auth, "{\"name\":\"Energia\",\"fixed\":true}", 201).get("id").asLong();
    long salary = send(post("/api/categories"), auth, "{\"name\":\"Stipendio\"}", 201).get("id").asLong();
    send(post("/api/categories/rules"), auth, "{\"categoryId\":" + bills + ",\"pattern\":\"enel\"}", 201);
    // more precise (longer): wins over "enel"
    send(post("/api/categories/rules"), auth, "{\"categoryId\":" + energy + ",\"pattern\":\"enel   energia\"}", 201);
    // only money in
    send(post("/api/categories/rules"), auth, "{\"categoryId\":" + salary + ",\"pattern\":\"acme\",\"direction\":\"IN\"}", 201);

    upload(auth, "[" + movement("Prova", "2026-09-05", "ADDEBITO ENEL ENERGIA SPA", "-80.10") + ","
        + movement("Prova", "2026-09-06", "Enel servizio elettrico", "-20") + ","
        + movement("Prova", "2026-09-27", "Stipendio ACME srl", "2000") + ","
        + movement("Prova", "2026-09-28", "Rimborso ACME", "-15") + ","
        + movement("Prova", "2026-09-29", "Caffe", "-1.20") + "]");
    String id = AccountPages.idOf("Prova");
    JsonNode items = movements(auth, id, "{}").get("items");
    assertEquals("Energia", find(items, "ADDEBITO ENEL ENERGIA SPA").get("category").asString());
    assertEquals("Bollette", find(items, "Enel servizio elettrico").get("category").asString());
    assertEquals("Stipendio", find(items, "Stipendio ACME srl").get("category").asString());
    assertTrue(find(items, "Rimborso ACME").get("category").isNull(), "money out: not the salary");
    assertTrue(find(items, "Caffe").get("category").isNull());
    assertEquals(2, movements(auth, id, "{\"uncategorized\":true}").get("items").size());
    assertEquals(1, movements(auth, id, "{\"uncategorized\":true,\"kind\":\"OUT\",\"q\":\"caffe\"}").get("items").size());

    // by hand, with a rule from it: the answer says what "apply to all" would change
    long coffee = find(items, "Caffe").get("id").asLong();
    long food = send(post("/api/categories"), auth, "{\"name\":\"Bar\"}", 201).get("id").asLong();
    upload(auth, "[" + movement("Prova", "2026-10-02", "CAFFE CENTRALE", "-1.10") + "]");
    JsonNode assigned = send(put("/api/movements/{id}/category", coffee), auth, "{\"categoryId\":" + food
        + ",\"rule\":{\"pattern\":\"caffe\",\"direction\":\"OUT\",\"onlyThisAccount\":true}}", 200);
    assertEquals("Bar", assigned.get("category").asString());
    assertEquals("MANUAL", assigned.get("categorySource").asString());
    assertEquals("caffe", assigned.get("rule").get("pattern").asString());
    assertEquals(id, assigned.get("rule").get("accountId").asString());
    assertEquals(1, assigned.get("preview").get("categorized").asInt(), "CAFFE CENTRALE, uploaded before the rule");
    assertEquals(1, assigned.get("preview").get("total").asInt());
    assertEquals(1, assigned.get("preview").get("manualKept").asLong());

    // a movement given by hand keeps its category, whatever the rules say
    long enel = find(items, "Enel servizio elettrico").get("id").asLong();
    send(put("/api/movements/{id}/category", enel), auth, "{\"categoryId\":" + energy + "}", 200);
    JsonNode applied = send(post("/api/categories/apply"), auth, "{\"dryRun\":false}", 200);
    assertEquals(1, applied.get("categorized").asInt());
    assertEquals(0, applied.get("changed").asInt());
    assertEquals(2, applied.get("manualKept").asLong());
    JsonNode now = movements(auth, id, "{}").get("items");
    assertEquals("Energia", find(now, "Enel servizio elettrico").get("category").asString());
    assertEquals("MANUAL", find(now, "Enel servizio elettrico").get("categorySource").asString());
    assertEquals("Bar", find(now, "CAFFE CENTRALE").get("category").asString());
    assertEquals("RULE", find(now, "CAFFE CENTRALE").get("categorySource").asString());

    // a new file: the rule gives the category at once; the same file again changes nothing
    String file = "[" + movement("Prova", "2026-10-03", "Caffe al banco", "-1.30") + "]";
    upload(auth, file);
    upload(auth, file);
    assertEquals("Bar", find(movements(auth, id, "{\"q\":\"banco\"}").get("items"), "Caffe al banco").get("category").asString());

    // no category, by hand: the movement is free again
    JsonNode removed = send(put("/api/movements/{id}/category", enel), auth, "{\"categoryId\":null}", 200);
    assertTrue(removed.get("categoryId").isNull());
    assertTrue(removed.get("preview").isNull());
    refused(put("/api/movements/{id}/category", enel), auth, "{\"categoryId\":999999}", "CATEGORY");
    refused(put("/api/movements/{id}/category", enel), auth, "{\"rule\":{\"pattern\":\"enel\"}}", "CATEGORY");
    mvc.perform(put("/api/movements/{id}/category", 99999999).header("Authorization", auth)
        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());

    // another user sees nothing of this
    String other = register(false);
    assertEquals(0, read(other, "/api/categories").get("categories").size());
    mvc.perform(put("/api/movements/{id}/category", coffee).header("Authorization", other)
        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
    mvc.perform(get("/api/fixed-costs")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/categories")).andExpect(status().isUnauthorized());
  }

  @Test
  void withoutFixedCategoriesTheNumbersAreEmptyNotAnError() throws Exception {
    String auth = register(false);
    JsonNode costs = read(auth, "/api/fixed-costs?months=6");
    assertEquals(6, costs.get("months").asInt());
    assertEquals(0, costs.get("monthsUsed").asInt());
    assertTrue(costs.get("monthly").isNull());
    assertEquals(0, costs.get("categories").size());
    assertEquals(0, costs.get("upcoming").size());
    assertEquals(36, read(auth, "/api/fixed-costs?months=500").get("months").asInt());
    mvc.perform(get("/api/fixed-costs?months=x")
        .header("Authorization", auth)).andExpect(status().isBadRequest());
  }

  @Test
  void theMigrationKeepsEveryMovementAsItWasWithoutACategory() {
    String schema = "mig" + UUID.randomUUID().toString().substring(0, 8);
    org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema)
        .locations("classpath:db/tenant").target("7").load().migrate();
    jdbc.update("INSERT INTO " + schema + ".bank_transaction (account, datetime, description, amount, fee, currency, "
        + "cumulative_amount, source, external_ref) VALUES ('A', '2025-01-01 10:00', 'x', -1, 0, 'EUR', 10, 'FILE', null), "
        + "('A', '2025-01-01 10:00', 'x', -1, 0, 'EUR', 11, 'MANUAL', null), ('B', '2025-01-02', 'y', 5, 0, 'USD', null, 'BANK', 'e-1')");
    String before = jdbc.queryForList("SELECT id, account, datetime, description, amount, fee, currency, cumulative_amount, "
        + "source, external_ref FROM " + schema + ".bank_transaction ORDER BY id").toString();

    tenantSchemas.migrate(schema);
    String after = jdbc.queryForList("SELECT id, account, datetime, description, amount, fee, currency, cumulative_amount, "
        + "source, external_ref FROM " + schema + ".bank_transaction ORDER BY id").toString();
    assertEquals(before, after);
    assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM " + schema
        + ".bank_transaction WHERE category_id IS NULL AND category_source IS NULL", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".category", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".category_rule", Integer.class));
    jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
  }
}
