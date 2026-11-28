package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import com.rondinella.moneymanageapi.demo.SampleData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The net worth over time and the newest movements of every account, for the overview. Made-up data only. */
@SpringBootTest(properties = "app.auth.max-registrations=10000")
@AutoConfigureMockMvc
class NetWorthTests {

  private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  /** the balances of the two sample accounts today, and the value of the sample flat */
  private static final BigDecimal SAMPLE_CASH = new BigDecimal("12600.25");
  private static final BigDecimal SAMPLE_FLAT = new BigDecimal("168000");

  @Autowired
  MockMvc mvc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  private String register(boolean sampleData) throws Exception {
    String body = "{\"username\":\"u" + UUID.randomUUID().toString().substring(0, 8)
        + "\",\"password\":\"password123\",\"sampleData\":" + sampleData + "}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    Matcher m = TOKEN.matcher(json);
    assertTrue(m.find());
    return "Bearer " + m.group(1);
  }

  private JsonNode json(String auth, String url, String... params) throws Exception {
    var request = get(url).header("Authorization", auth);
    for (int i = 0; i + 1 < params.length; i += 2) {
      request.param(params[i], params[i + 1]);
    }
    return JSON.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private static BigDecimal dec(JsonNode node) {
    return node.decimalValue();
  }

  private static LocalDate today() {
    return LocalDate.now(AccountPages.ZONE);
  }

  @Test
  void netWorthOfTheSampleDataTodayAndAtTheEndOfEveryMonth() throws Exception {
    String auth = register(true);
    LocalDate from = today().minusMonths(6);
    JsonNode answer = json(auth, "/api/net-worth", "from", from.toString());

    assertEquals("EUR", answer.get("currency").asString());
    assertEquals(2, answer.get("accounts").asInt());
    assertEquals(1, answer.get("realEstates").asInt());
    assertEquals(1, answer.get("instruments").asInt());
    assertEquals(0, answer.get("excluded").get("accounts").asInt());
    assertEquals(0, answer.get("excluded").get("currencies").size());

    JsonNode today = answer.get("today");
    assertEquals(today().toString(), today.get("date").asString());
    assertEquals(0, SAMPLE_CASH.compareTo(dec(today.get("cash"))));
    assertEquals(0, SAMPLE_FLAT.compareTo(dec(today.get("realEstate"))));
    assertTrue(dec(today.get("investments")).signum() > 0, "the sample ETF is worth something");
    assertEquals(0, BigDecimal.ZERO.compareTo(dec(today.get("debts"))));
    assertEquals(0, dec(today.get("cash")).add(dec(today.get("investments"))).add(dec(today.get("realEstate")))
        .compareTo(dec(today.get("total"))));
    assertEquals(today().minusDays(30).toString(), answer.get("before").get("date").asString());

    // from, then the end of every month, then today; oldest first
    JsonNode points = answer.get("points");
    assertEquals(from.toString(), points.get(0).get("date").asString());
    assertEquals(today().toString(), points.get(points.size() - 1).get("date").asString());
    assertTrue(points.size() >= 7 && points.size() <= 8, "6 months: " + points.size());
    for (int i = 1; i < points.size() - 1; i++) {
      LocalDate day = LocalDate.parse(points.get(i).get("date").asString());
      assertEquals(day.lengthOfMonth(), day.getDayOfMonth(), "end of the month: " + day);
      assertTrue(day.isAfter(LocalDate.parse(points.get(i - 1).get("date").asString())));
      assertTrue(dec(points.get(i).get("cash")).signum() > 0);
    }
    assertEquals(today, points.get(points.size() - 1));
  }

  @Test
  void beforeTheFirstDataThereIsNothingAndWithoutFromItStartsAtTheFirstDay() throws Exception {
    String auth = register(true);
    // the sample flat was bought on 15 June 2019, the bank movements and the ETF are much newer
    JsonNode old = json(auth, "/api/net-worth", "from", "2019-01-01").get("points");
    assertEquals(0, BigDecimal.ZERO.compareTo(dec(old.get(5).get("total"))), "nothing before June 2019");
    assertEquals("2019-06-30", old.get(6).get("date").asString());
    assertEquals(0, SAMPLE_FLAT.compareTo(dec(old.get(6).get("total"))), "only the flat in 2019");

    JsonNode all = json(auth, "/api/net-worth");
    assertEquals("2019-06-15", all.get("from").asString());
    assertEquals(0, SAMPLE_FLAT.compareTo(dec(all.get("points").get(0).get("realEstate"))));

    // a date in the future is today
    JsonNode future = json(auth, "/api/net-worth", "from", today().plusYears(1).toString());
    assertEquals(1, future.get("points").size());
    mvc.perform(get("/api/net-worth")
            .param("from", "1900-01-01").header("Authorization", auth))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/net-worth")
            .param("from", "not-a-date").header("Authorization", auth))
        .andExpect(status().isBadRequest());
  }

  @Test
  void onlyEuroInTheTotalOtherCurrenciesAreCountedApart() throws Exception {
    String auth = register(true);
    long now = LocalDateTime.now().minusHours(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    mvc.perform(post("/api/banks/transactions").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
            .content("[{\"account\":\"Conto in dollari (esempio)\",\"datetime\":" + now
                + ",\"description\":\"Deposito\",\"amount\":500,\"fee\":0,\"currency\":\"USD\"}]"))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/banks/transactions/accounts/{a}/balance", "Conto in dollari (esempio)")
            .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"amountToday\":500}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/realestate").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Chalet (esempio)\",\"purchaseDate\":\"2020-01-01\",\"currentEstimatedValue\":300000,"
                + "\"currency\":\"CHF\"}"))
        .andExpect(status().is2xxSuccessful());

    JsonNode answer = json(auth, "/api/net-worth", "from", today().minusMonths(1).toString());
    assertEquals(2, answer.get("accounts").asInt());
    assertEquals(1, answer.get("realEstates").asInt());
    assertEquals(1, answer.get("excluded").get("accounts").asInt());
    assertEquals(1, answer.get("excluded").get("realEstates").asInt());
    assertEquals("CHF", answer.get("excluded").get("currencies").get(0).asString());
    assertEquals("USD", answer.get("excluded").get("currencies").get(1).asString());
    assertEquals(0, SAMPLE_CASH.compareTo(dec(answer.get("today").get("cash"))), "the dollars are not in the cash");
    assertEquals(0, SAMPLE_FLAT.compareTo(dec(answer.get("today").get("realEstate"))));
  }

  @Test
  void aNewUserHasNothingAndNeverSeesTheDataOfAnother() throws Exception {
    register(true);
    String empty = register(false);
    JsonNode answer = json(empty, "/api/net-worth");
    assertEquals(0, answer.get("accounts").asInt());
    assertEquals(0, answer.get("realEstates").asInt());
    assertEquals(0, answer.get("instruments").asInt());
    assertEquals(1, answer.get("points").size());
    assertEquals(0, BigDecimal.ZERO.compareTo(dec(answer.get("today").get("total"))));
    assertEquals(0, json(empty, "/api/money-accounts/movements/latest").size());

    mvc.perform(get("/api/net-worth"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/money-accounts/movements/latest"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void theNewestMovementsOfEveryAccountWithTheirAccount() throws Exception {
    String auth = register(true);
    JsonNode latest = json(auth, "/api/money-accounts/movements/latest");
    assertEquals(8, latest.size());
    for (int i = 1; i < latest.size(); i++) {
      assertTrue(latest.get(i - 1).get("datetime").asString().compareTo(latest.get(i).get("datetime").asString()) >= 0,
          "newest first");
    }
    JsonNode first = latest.get(0);
    assertTrue(first.get("account").asString().equals(SampleData.CHECKING) || first.get("account").asString().equals(SampleData.SAVINGS));
    assertEquals(AccountPages.idOf(first.get("account").asString()), first.get("accountId").asString());
    assertEquals("EUR", first.get("currency").asString());
    assertNotNull(first.get("source").asString());

    assertEquals(3, json(auth, "/api/money-accounts/movements/latest", "limit", "3").size());
    assertEquals(50, json(auth, "/api/money-accounts/movements/latest", "limit", "1000").size());
  }
}
