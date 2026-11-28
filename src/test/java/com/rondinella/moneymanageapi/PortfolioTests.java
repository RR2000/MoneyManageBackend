package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import com.rondinella.moneymanageapi.demo.SampleData;
import com.rondinella.moneymanageapi.stocks.AlphaVantage;
import com.rondinella.moneymanageapi.stocks.YahooPrices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Positions, returns and allocation of the investments page, on trades built by hand and on the sample data. */
@SpringBootTest(properties = "app.auth.max-registrations=10000")
@AutoConfigureMockMvc
class PortfolioTests {

  private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter DEGIRO = DateTimeFormatter.ofPattern("dd-MM-yyyy");

  @Autowired
  MockMvc mvc;
  /** no market in the tests: the prices come from a CSV */
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

  private String register(boolean sampleData) throws Exception {
    String body = "{\"username\":\"p" + UUID.randomUUID().toString().substring(0, 8)
        + "\",\"password\":\"password123\",\"sampleData\":" + sampleData + "}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    Matcher m = TOKEN.matcher(json);
    assertTrue(m.find());
    return "Bearer " + m.group(1);
  }

  private static String isin(String country) {
    return country + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
  }

  private static LocalDate today() {
    return LocalDate.now(AccountPages.ZONE);
  }

  private static String day(int daysAgo) {
    return today().minusDays(daysAgo).format(DEGIRO);
  }

  private JsonNode investments(String auth) throws Exception {
    return JSON.readTree(mvc.perform(get("/api/investments").header("Authorization", auth))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private void putTargets(String auth, String body, int status, String message) throws Exception {
    var result = mvc.perform(put("/api/investments/targets").header("Authorization", auth)
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(status));
    if (message != null) {
      result.andExpect(status().reason(message));
    }
  }

  private static BigDecimal dec(JsonNode node) {
    return node.decimalValue();
  }

  private static void same(String expected, JsonNode actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(dec(actual)), expected + " != " + actual);
  }

  /**
   * An ETF in euro: 10 at 100 (fee 2) 400 days ago, 10 at 120 (fee 2) 200 days ago, 5 sold at 130 (fee 2) 100 days ago,
   * last price 140; a share in dollars. Prices from a CSV.
   */
  private String handMade(String auth) throws Exception {
    String etf = isin("IE");
    String usd = isin("US");
    String csv = "Data,Ora,Prodotto,ISIN,Quantità,Quotazione,,Commissioni di\n"
        + day(400) + ",10:00,ETF A MANO," + etf + ",10,100,EUR,-2\n"
        + day(200) + ",10:00,ETF A MANO," + etf + ",10,120,EUR,-2\n"
        + day(100) + ",10:00,ETF A MANO," + etf + ",-5,130,EUR,-2\n"
        + day(300) + ",15:30,AZIONE IN DOLLARI," + usd + ",3,150,USD,-1\n";
    mvc.perform(post("/api/brokers/transactions/upload").header("Authorization", auth)
            .contentType("text/csv;charset=UTF-8").content(csv.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isCreated());
    String prices = "date,price\n" + today().minusDays(400) + ",100\n" + today().minusDays(200) + ",120\n"
        + today().minusDays(100) + ",130\n" + today().minusDays(2) + ",140\n";
    mvc.perform(post("/api/instruments/{isin}/prices", etf).header("Authorization", auth).contentType("text/csv")
        .content(prices)).andExpect(status().isOk());
    mvc.perform(post("/api/instruments/{isin}/prices", usd).header("Authorization", auth).contentType("text/csv")
        .content(today().minusDays(2) + ",170\n")).andExpect(status().isOk());
    return etf;
  }

  @Test
  void averageCostGainTaxesAndTotalsOnTradesBuiltByHand() throws Exception {
    String auth = register(false);
    String etf = handMade(auth);
    JsonNode answer = investments(auth);

    assertEquals("EUR", answer.get("currency").asString());
    same("0.26", answer.get("taxRate"));
    JsonNode positions = answer.get("positions");
    assertEquals(2, positions.size());
    JsonNode p = positions.get(0);
    assertEquals(etf, p.get("isin").asString());
    assertEquals("ETF A MANO", p.get("name").asString());
    assertTrue(p.get("inTotal").asBoolean());
    same("15", p.get("quantity"));
    // (10 * 100 + 2 + 10 * 120 + 2) / 20 = 110.2; the sale takes out 5 * 110.2 = 551
    same("110.2", p.get("averagePrice"));
    same("1653.00", p.get("invested"));
    same("140", p.get("lastPrice"));
    assertEquals(today().minusDays(2).toString(), p.get("lastDate").asString());
    same("2100.00", p.get("value"));
    same("447.00", p.get("gain"));
    assertEquals(447.0 / 1653, p.get("gainShare").asDouble(), 1e-8);
    same("116.22", p.get("tax"));
    same("330.78", p.get("netGain"));
    // 5 * 130 - 2 - 551
    same("97.00", p.get("realized"));
    assertEquals(1.0, p.get("portfolioShare").asDouble(), 1e-12);

    // the dollars: flagged, not converted, not in the totals
    JsonNode usd = positions.get(1);
    assertFalse(usd.get("inTotal").asBoolean());
    assertEquals("CURRENCY", usd.get("excluded").asString());
    assertEquals("USD", usd.get("currency").asString());
    assertTrue(usd.get("portfolioShare").isNull());
    assertEquals(1, answer.get("excluded").get("instruments").asInt());
    assertEquals("USD", answer.get("excluded").get("currencies").get(0).asString());

    JsonNode totals = answer.get("totals");
    same("1653.00", totals.get("invested"));
    same("2100.00", totals.get("value"));
    same("447.00", totals.get("gain"));
    same("116.22", totals.get("tax"));
    same("330.78", totals.get("netGain"));
    same("97.00", totals.get("realized"));
    same("6.00", totals.get("fees"));
    same("2204.00", totals.get("bought"));
    same("648.00", totals.get("sold"));

    // returns: money-weighted on the flows with their days, time-weighted on the months
    JsonNode perf = answer.get("performance");
    assertEquals(today().minusDays(400).toString(), perf.get("since").asString());
    assertEquals(400, perf.get("days").asLong());
    same("544.00", perf.get("simpleGain")); // 2100 + 648 - 2204 = 447 + 97
    assertEquals(544.0 / 2204, perf.get("simple").asDouble(), 1e-8);
    double r = perf.get("xirr").asDouble();
    double npv = -1002 - 1202 * Math.pow(1 + r, -200 / 365.0) + 648 * Math.pow(1 + r, -300 / 365.0)
        + 2100 * Math.pow(1 + r, -400 / 365.0);
    assertEquals(0, npv, 1e-6, "the rate makes the flows worth zero: " + r);
    assertTrue(r > 0.15 && r < 0.40, "about a quarter more in a bit more than a year: " + r);
    assertTrue(perf.get("twr").asDouble() > 0);
    assertEquals(Math.pow(1 + perf.get("twr").asDouble(), 365.0 / 400) - 1, perf.get("twrYearly").asDouble(), 1e-9);

    // history: from the first trade, the end of every month, today; worth and money put in (purchases minus sales)
    JsonNode history = answer.get("history");
    assertEquals(today().minusDays(400).toString(), history.get(0).get("date").asString());
    same("1000.00", history.get(0).get("value"));
    same("1002.00", history.get(0).get("invested"));
    JsonNode last = history.get(history.size() - 1);
    assertEquals(today().toString(), last.get("date").asString());
    same("2100.00", last.get("value"));
    same("1556.00", last.get("invested"));
    same("2100.00", last.get("byIsin").get(etf));
    assertNull(last.get("byIsin").get(usd.get("isin").asString()), "not in euro: not in the history");
  }

  @Test
  void targetsAreSavedCheckedAndComparedWithThePortfolio() throws Exception {
    String auth = register(false);
    String etf = handMade(auth);
    String usd = investments(auth).get("positions").get(1).get("isin").asString();

    JsonNode empty = investments(auth).get("allocation");
    same("0", empty.get("assignedPercent"));
    same("100", empty.get("unassignedPercent"));
    assertTrue(empty.get("slices").get(0).get("targetPercent").isNull());

    putTargets(auth, "{\"targets\":[{\"isin\":\"" + etf + "\",\"percent\":60},{\"isin\":\"" + usd
        + "\",\"percent\":15.5}]}", 200, null);
    mvc.perform(get("/api/investments/targets").header("Authorization", auth)).andExpect(status().isOk())
        .andExpect(jsonPath("$.targets.length()").value(2))
        .andExpect(jsonPath("$.unassignedPercent").value(24.5));
    JsonNode allocation = investments(auth).get("allocation");
    same("75.5", allocation.get("assignedPercent"));
    same("24.5", allocation.get("unassignedPercent"));
    assertEquals(1, allocation.get("slices").size(), "only the positions in euro");
    JsonNode slice = allocation.get("slices").get(0);
    same("100", slice.get("actualPercent"));
    same("60", slice.get("targetPercent"));
    same("-840.00", slice.get("gap")); // 60% of 2100 is 1260: 840 too much
    same("60", investments(auth).get("positions").get(0).get("targetPercent"));

    // 0 takes the target away; the others are replaced
    putTargets(auth, "{\"targets\":[{\"isin\":\"" + etf + "\",\"percent\":0}]}", 200, null);
    mvc.perform(get("/api/investments/targets").header("Authorization", auth))
        .andExpect(jsonPath("$.targets.length()").value(0)).andExpect(jsonPath("$.unassignedPercent").value(100));

    putTargets(auth, "{\"targets\":[{\"isin\":\"" + etf + "\",\"percent\":100.01}]}", 400, "TARGET_RANGE");
    putTargets(auth, "{\"targets\":[{\"isin\":\"" + etf + "\",\"percent\":-1}]}", 400, "TARGET_RANGE");
    putTargets(auth, "{\"targets\":[{\"isin\":\"" + etf + "\",\"percent\":10.125}]}", 400, "TARGET_RANGE");
    putTargets(auth, "{\"targets\":[{\"isin\":\"" + etf + "\"}]}", 400, "TARGET_RANGE");
    putTargets(auth, "{\"targets\":[{\"isin\":\"" + etf + "\",\"percent\":60},{\"isin\":\"" + usd
        + "\",\"percent\":40.01}]}", 400, "TARGET_SUM");
    putTargets(auth, "{\"targets\":[{\"isin\":\"" + etf + "\",\"percent\":10},{\"isin\":\"" + etf
        + "\",\"percent\":10}]}", 400, "DUPLICATE_ISIN");
    putTargets(auth, "{\"targets\":[{\"isin\":\"XX9999999999\",\"percent\":10}]}", 400, "UNKNOWN_ISIN");
    mvc.perform(get("/api/investments/targets").header("Authorization", auth))
        .andExpect(jsonPath("$.targets.length()").value(0));

    putTargets(auth, "{\"targets\":[{\"isin\":\"" + etf + "\",\"percent\":100}]}", 200, null);
    same("0.00", investments(auth).get("allocation").get("slices").get(0).get("gap"));
  }

  @Test
  void theSampleDataHaveTwoFundsASaleAndTheirHistory() throws Exception {
    String auth = register(true);
    JsonNode answer = investments(auth);
    JsonNode positions = answer.get("positions");
    assertEquals(2, positions.size());
    double shares = 0;
    for (JsonNode p : positions) {
      assertTrue(p.get("inTotal").asBoolean());
      assertTrue(p.get("isin").asString().equals(SampleData.ETF_ISIN) || p.get("isin").asString().equals(SampleData.BOND_ISIN));
      assertTrue(dec(p.get("value")).signum() > 0);
      same(dec(p.get("gain")).subtract(dec(p.get("tax"))).toPlainString(), p.get("netGain"));
      shares += p.get("portfolioShare").asDouble();
    }
    assertEquals(1.0, shares, 1e-6);
    assertTrue(dec(answer.get("totals").get("realized")).signum() != 0, "a few of the fund were sold");
    assertEquals(0, answer.get("excluded").get("instruments").asInt());

    JsonNode perf = answer.get("performance");
    assertFalse(perf.get("xirr").isNull());
    assertFalse(perf.get("twr").isNull());
    assertEquals(today().minusYears(3).withDayOfMonth(3).toString(), perf.get("since").asString());

    JsonNode history = answer.get("history");
    assertTrue(history.size() >= 36, "every month of three years: " + history.size());
    JsonNode last = history.get(history.size() - 1);
    assertEquals(today().toString(), last.get("date").asString());
    same(answer.get("totals").get("value").decimalValue().toPlainString(), last.get("value"));
    BigDecimal bought = dec(answer.get("totals").get("bought"));
    BigDecimal sold = dec(answer.get("totals").get("sold"));
    same(bought.subtract(sold).toPlainString(), last.get("invested"));
    assertEquals(ChronoUnit.DAYS.between(LocalDate.parse(perf.get("since").asString()), today()),
        perf.get("days").asLong());
  }

  @Test
  void aNewUserHasNothingAndNeverSeesOrSetsTheInvestmentsOfAnother() throws Exception {
    String owner = register(false);
    String etf = handMade(owner);
    putTargets(owner, "{\"targets\":[{\"isin\":\"" + etf + "\",\"percent\":50}]}", 200, null);

    String other = register(false);
    JsonNode answer = investments(other);
    assertEquals(0, answer.get("positions").size());
    same("0", answer.get("totals").get("value"));
    assertTrue(answer.get("performance").get("xirr").isNull());
    assertEquals(0, answer.get("history").size());
    assertEquals(0, answer.get("allocation").get("slices").size());
    same("100", answer.get("allocation").get("unassignedPercent"));
    mvc.perform(get("/api/investments/targets").header("Authorization", other))
        .andExpect(jsonPath("$.targets.length()").value(0));
    putTargets(other, "{\"targets\":[{\"isin\":\"" + etf + "\",\"percent\":10}]}", 400, "UNKNOWN_ISIN");
    same("50", investments(owner).get("allocation").get("slices").get(0).get("targetPercent"));

    mvc.perform(get("/api/investments")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/investments/targets")).andExpect(status().isUnauthorized());
    mvc.perform(put("/api/investments/targets").contentType(MediaType.APPLICATION_JSON).content("{\"targets\":[]}"))
        .andExpect(status().isUnauthorized());
  }
}
