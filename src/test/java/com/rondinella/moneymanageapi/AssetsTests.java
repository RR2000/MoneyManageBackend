package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The assets page: real estate, other assets and debts, their totals by currency, the rules and the users apart. */
@SpringBootTest(properties = "app.auth.max-registrations=10000")
@AutoConfigureMockMvc
class AssetsTests {

  private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired
  MockMvc mvc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  private String register(boolean sampleData) throws Exception {
    String body = "{\"username\":\"a" + UUID.randomUUID().toString().substring(0, 8)
        + "\",\"password\":\"password123\",\"sampleData\":" + sampleData + "}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    Matcher m = TOKEN.matcher(json);
    assertTrue(m.find());
    return "Bearer " + m.group(1);
  }

  private static LocalDate today() {
    return LocalDate.now(AccountPages.ZONE);
  }

  private JsonNode assets(String auth) throws Exception {
    return JSON.readTree(mvc.perform(get("/api/assets").header("Authorization", auth))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
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

  @Test
  void theSampleDataHaveAFlatACarGoldAndACarLoan() throws Exception {
    JsonNode a = assets(register(true));
    assertEquals("EUR", a.get("currency").asString());
    assertEquals(1, a.get("realEstates").size());
    assertEquals("Bilocale (esempio)", a.get("realEstates").get(0).get("name").asString());

    JsonNode others = a.get("otherAssets");
    assertEquals(2, others.size());
    assertEquals("Auto (esempio)", others.get(0).get("name").asString(), "largest first");
    assertEquals("VEHICLE", others.get(0).get("kind").asString());
    same("9500", others.get(0).get("value"));
    assertEquals(today().minusMonths(3).withDayOfMonth(1).toString(), others.get(0).get("valuedOn").asString());
    assertEquals("GOLD", others.get(1).get("kind").asString());
    assertTrue(others.get(1).get("valuedOn").isNull());

    JsonNode debt = a.get("debts").get(0);
    same("6200", debt.get("balance"));
    same("5.9", debt.get("annualRate"));
    same("280", debt.get("monthlyPayment"));
    assertEquals("ESTIMATE", debt.get("payoff").get("kind").asString());
    assertEquals(24, debt.get("payoff").get("monthsLeft").asInt());
    assertEquals(today().plusMonths(24).toString(), debt.get("payoff").get("expectedEnd").asString());

    JsonNode totals = a.get("totals");
    assertEquals(1, totals.size());
    assertEquals("EUR", totals.get(0).get("currency").asString());
    same("168000", totals.get(0).get("realEstate"));
    same("11900", totals.get(0).get("otherAssets"));
    same("-6200", totals.get(0).get("debts"));
    same("173700", totals.get(0).get("net"));
  }

  @Test
  void otherAssetsAndDebtsAreCreatedChangedAndDeleted() throws Exception {
    String auth = register(false);
    JsonNode empty = assets(auth);
    assertEquals(0, empty.get("otherAssets").size());
    assertEquals(1, empty.get("totals").size(), "euro is always there");
    same("0", empty.get("totals").get(0).get("net"));

    JsonNode gold = send(post("/api/assets/others"), auth,
        "{\"name\":\" Monete (esempio) \",\"kind\":\"GOLD\",\"value\":1200.5,\"currency\":\"eur\",\"valuedOn\":\""
            + today().minusDays(10) + "\"}", 201);
    assertEquals("Monete (esempio)", gold.get("name").asString());
    assertEquals("EUR", gold.get("currency").asString());
    long goldId = gold.get("id").asLong();
    JsonNode changed = send(put("/api/assets/others/{id}", goldId), auth,
        "{\"name\":\"Monete (esempio)\",\"kind\":\"GOLD\",\"value\":1300,\"currency\":\"EUR\"}", 200);
    same("1300", changed.get("value"));
    assertTrue(changed.get("valuedOn").isNull());

    JsonNode loan = send(post("/api/assets/debts"), auth,
        "{\"name\":\"Mutuo (esempio)\",\"balance\":10000,\"currency\":\"EUR\",\"annualRate\":6,\"monthlyPayment\":300}", 201);
    assertEquals("ESTIMATE", loan.get("payoff").get("kind").asString());
    assertEquals(37, loan.get("payoff").get("monthsLeft").asInt());
    long loanId = loan.get("id").asLong();
    LocalDate end = today().plusYears(2);
    JsonNode dated = send(put("/api/assets/debts/{id}", loanId), auth,
        "{\"name\":\"Mutuo (esempio)\",\"balance\":9000,\"currency\":\"EUR\",\"endDate\":\"" + end + "\"}", 200);
    assertEquals("END_DATE", dated.get("payoff").get("kind").asString());
    assertEquals(end.toString(), dated.get("payoff").get("expectedEnd").asString());
    assertTrue(dated.get("annualRate").isNull());

    JsonNode a = assets(auth);
    same("1300", a.get("totals").get(0).get("otherAssets"));
    same("-9000", a.get("totals").get(0).get("debts"));
    same("-7700", a.get("totals").get(0).get("net"));

    mvc.perform(delete("/api/assets/others/{id}", goldId).header("Authorization", auth)).andExpect(status().isNoContent());
    mvc.perform(delete("/api/assets/debts/{id}", loanId).header("Authorization", auth)).andExpect(status().isNoContent());
    mvc.perform(delete("/api/assets/debts/{id}", loanId).header("Authorization", auth)).andExpect(status().isNotFound());
    send(put("/api/assets/others/{id}", goldId), auth,
        "{\"name\":\"x\",\"kind\":\"GOLD\",\"value\":1,\"currency\":\"EUR\"}", 404);
    assertEquals(0, assets(auth).get("otherAssets").size());
    assertEquals(0, assets(auth).get("debts").size());
  }

  @Test
  void whatIsRefusedSaysWhyWithACode() throws Exception {
    String auth = register(false);
    refused(post("/api/assets/others"), auth, "{\"kind\":\"GOLD\",\"value\":1,\"currency\":\"EUR\"}", "NAME_REQUIRED");
    refused(post("/api/assets/others"), auth, "{\"name\":\"x\",\"kind\":\"BOAT\",\"value\":1,\"currency\":\"EUR\"}", "KIND");
    refused(post("/api/assets/others"), auth, "{\"name\":\"x\",\"kind\":\"GOLD\",\"currency\":\"EUR\"}", "AMOUNT_REQUIRED");
    refused(post("/api/assets/others"), auth, "{\"name\":\"x\",\"kind\":\"GOLD\",\"value\":-1,\"currency\":\"EUR\"}", "AMOUNT_RANGE");
    refused(post("/api/assets/others"), auth, "{\"name\":\"x\",\"kind\":\"GOLD\",\"value\":1,\"currency\":\"EU\"}", "CURRENCY");
    refused(post("/api/assets/others"), auth, "{\"name\":\"x\",\"kind\":\"GOLD\",\"value\":1,\"currency\":\"EUR\",\"valuedOn\":\""
        + today().plusDays(1) + "\"}", "DATE_IN_FUTURE");
    refused(post("/api/assets/debts"), auth, "{\"name\":\"x\",\"currency\":\"EUR\"}", "AMOUNT_REQUIRED");
    refused(post("/api/assets/debts"), auth, "{\"name\":\"x\",\"balance\":1,\"currency\":\"EUR\",\"annualRate\":101}", "RATE_RANGE");
    refused(post("/api/assets/debts"), auth, "{\"name\":\"x\",\"balance\":1,\"currency\":\"EUR\",\"monthlyPayment\":-5}", "AMOUNT_RANGE");
    refused(post("/api/assets/debts"), auth, "{\"name\":\"x\",\"balance\":1,\"currency\":\"EUR\",\"endDate\":\"1800-01-01\"}", "DATE_RANGE");
    refused(post("/api/realestate"), auth, "{\"name\":\"\",\"currentEstimatedValue\":1,\"currency\":\"EUR\"}", "NAME_REQUIRED");
    refused(post("/api/realestate"), auth, "{\"name\":\"Casa\",\"currency\":\"EUR\"}", "AMOUNT_REQUIRED");
    refused(post("/api/realestate"), auth, "{\"name\":\"Casa\",\"currentEstimatedValue\":1,\"monthlyRent\":-1,\"currency\":\"EUR\"}", "AMOUNT_RANGE");
    refused(post("/api/realestate"), auth, "{\"name\":\"Casa\",\"currentEstimatedValue\":1}", "CURRENCY");
    // a date that is not a date is a bad request too
    mvc.perform(post("/api/assets/debts").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"x\",\"balance\":1,\"currency\":\"EUR\",\"endDate\":\"domani\"}")).andExpect(status().isBadRequest());
    JsonNode a = assets(auth);
    assertEquals(0, a.get("realEstates").size() + a.get("otherAssets").size() + a.get("debts").size(), "nothing saved");
  }

  @Test
  void otherCurrenciesHaveTheirOwnTotalsNeverAddedToTheEuro() throws Exception {
    String auth = register(true);
    send(post("/api/assets/others"), auth, "{\"name\":\"Conto in dollari (esempio)\",\"kind\":\"CASH\",\"value\":1000,\"currency\":\"USD\"}", 201);
    send(post("/api/assets/debts"), auth, "{\"name\":\"Debito in dollari (esempio)\",\"balance\":200,\"currency\":\"USD\"}", 201);
    send(post("/api/realestate"), auth, "{\"name\":\"Chalet (esempio)\",\"currentEstimatedValue\":300000,\"currency\":\"CHF\"}", 201);
    // a property bought in the future is listed, not yet counted
    send(post("/api/realestate"), auth, "{\"name\":\"Box (esempio)\",\"currentEstimatedValue\":20000,\"currency\":\"EUR\",\"purchaseDate\":\""
        + today().plusMonths(2) + "\"}", 201);

    JsonNode a = assets(auth);
    assertEquals(3, a.get("realEstates").size());
    JsonNode totals = a.get("totals");
    assertEquals(3, totals.size());
    assertEquals("EUR", totals.get(0).get("currency").asString());
    same("173700", totals.get(0).get("net"));
    assertEquals("CHF", totals.get(1).get("currency").asString());
    same("300000", totals.get(1).get("realEstate"));
    assertEquals("USD", totals.get(2).get("currency").asString());
    same("1000", totals.get(2).get("otherAssets"));
    same("-200", totals.get(2).get("debts"));
    same("800", totals.get(2).get("net"));
    JsonNode unpaid = a.get("debts").get(1);
    assertEquals("UNKNOWN", unpaid.get("payoff").get("kind").asString());
  }

  @Test
  void usersNeverSeeOrTouchEachOthersAssets() throws Exception {
    String alice = register(false);
    String bob = register(false);
    long gold = send(post("/api/assets/others"), alice,
        "{\"name\":\"Oro di Alice (esempio)\",\"kind\":\"GOLD\",\"value\":5000,\"currency\":\"EUR\"}", 201).get("id").asLong();
    long loan = send(post("/api/assets/debts"), alice,
        "{\"name\":\"Debito di Alice (esempio)\",\"balance\":700,\"currency\":\"EUR\"}", 201).get("id").asLong();
    String before = JSON.writeValueAsString(assets(alice));

    JsonNode b = assets(bob);
    assertEquals(0, b.get("otherAssets").size());
    assertEquals(0, b.get("debts").size());
    send(put("/api/assets/others/{id}", gold), bob, "{\"name\":\"Mio\",\"kind\":\"GOLD\",\"value\":1,\"currency\":\"EUR\"}", 404);
    send(put("/api/assets/debts/{id}", loan), bob, "{\"name\":\"Mio\",\"balance\":1,\"currency\":\"EUR\"}", 404);
    mvc.perform(delete("/api/assets/others/{id}", gold).header("Authorization", bob)).andExpect(status().isNotFound());
    mvc.perform(delete("/api/assets/debts/{id}", loan).header("Authorization", bob)).andExpect(status().isNotFound());
    // bob's own rows can have the same ids: they stay in his schema
    send(post("/api/assets/others"), bob, "{\"name\":\"Bici (esempio)\",\"kind\":\"VEHICLE\",\"value\":300,\"currency\":\"EUR\"}", 201);
    assertEquals(before, JSON.writeValueAsString(assets(alice)));
    assertEquals(1, assets(bob).get("otherAssets").size());

    mvc.perform(get("/api/assets")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/assets/debts").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }
}
