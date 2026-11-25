package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.demo.SampleData;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.auth.max-registrations=10000")
@AutoConfigureMockMvc
class MoneyManageApiTests {

  private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

  @Autowired
  MockMvc mvc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  private String register(String username, boolean sampleData) throws Exception {
    String body = "{\"username\":\"" + username + "\",\"password\":\"password123\",\"sampleData\":" + sampleData + "}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    Matcher m = TOKEN.matcher(json);
    if (!m.find()) throw new AssertionError("no token in " + json);
    return "Bearer " + m.group(1);
  }

  private static String user() {
    return "u" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void apiNeedsLogin() throws Exception {
    mvc.perform(get("/api/banks/transactions/accounts")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/api/auth/providers")).andExpect(jsonPath("$.github").value(false));
  }

  @Test
  void registerRulesAndLogin() throws Exception {
    String name = user();
    register(name, false);
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name + "\",\"password\":\"password123\"}"))
        .andExpect(status().isConflict());
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"x\",\"password\":\"password123\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + user() + "\",\"password\":\"short\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + user() + "\",\"password\":\"" + "x".repeat(80) + "\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name + "\",\"password\":\"password123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token", not(emptyString())));
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name + "\",\"password\":\"wrong-password\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void sampleDataFillsTheDashboard() throws Exception {
    String auth = register(user(), true);
    LocalDate today = LocalDate.now();
    String from = today.minusMonths(6) + " 00:00:00.000";
    String to = today + " 23:59:59.999";

    mvc.perform(get("/api/banks/transactions/accounts").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", containsInAnyOrder(SampleData.CHECKING, SampleData.SAVINGS)));
    String graph = mvc.perform(get("/api/banks/transactions/graph/{from}/{to}", from, to).header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uniqueGraphNames", hasItems(SampleData.CHECKING, SampleData.SAVINGS, "Total cash")))
        .andReturn().getResponse().getContentAsString();
    // the last point of the checking account is the balance given to the sample data
    var checking = JsonMapper.builder().build().readTree(graph).get("graphData").get(SampleData.CHECKING);
    var last = checking.properties().stream().reduce((x, y) -> y).orElseThrow();
    org.junit.jupiter.api.Assertions.assertEquals(0, last.getValue().decimalValue().compareTo(new java.math.BigDecimal("3480.25")));
    mvc.perform(get("/api/banks/transactions/table/{from}/{to}", from, to).param("accountName", SampleData.SAVINGS)
            .header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(greaterThan(3))));
    mvc.perform(get("/api/brokers/transactions/worth/graph").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uniqueGraphNames", hasItems(SampleData.ETF_ISIN, "Total Worth")));
    String mc = mvc.perform(get("/api/montecarlo").param("years", "2").param("simulations", "500").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.uniqueGraphNames", contains("P25", "P5", "P50", "P75", "P95")))
        .andReturn().getResponse().getContentAsString();
    // market returns only (monthly purchases excluded): the median stays in a sane range over two years
    var p50 = JsonMapper.builder().build().readTree(mc).get("graphData").get("P50").properties().stream()
        .map(e -> e.getValue().doubleValue()).toList();
    double growth = p50.getLast() / p50.getFirst();
    org.junit.jupiter.api.Assertions.assertTrue(growth > 0.7 && growth < 1.6, "P50 growth over 2 years: " + growth);
    mvc.perform(get("/api/realestate").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)));
    mvc.perform(get("/api/realestate/worth").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(content().string("168000.00"));
  }

  @Test
  void everyUserSeesOnlyTheirData() throws Exception {
    String alice = register(user(), true);
    String bob = register(user(), false);

    mvc.perform(get("/api/banks/transactions/accounts").header("Authorization", bob))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
    mvc.perform(get("/api/realestate").header("Authorization", bob))
        .andExpect(jsonPath("$", hasSize(0)));

    mvc.perform(post("/api/realestate").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Box\",\"currentEstimatedValue\":20000,\"currency\":\"EUR\"}"))
        .andExpect(status().isCreated());
    mvc.perform(get("/api/realestate").header("Authorization", bob)).andExpect(jsonPath("$", hasSize(1)));
    mvc.perform(get("/api/realestate").header("Authorization", alice))
        .andExpect(jsonPath("$[*].name", contains("Bilocale (esempio)")));
  }

  @Test
  void revolutCsvUploadAndBalances() throws Exception {
    String auth = register(user(), false);
    String csv = """
        Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance
        TOPUP,Current,2025-01-02 10:00:00,2025-01-02 10:00:01,Top-up,100.00,0.00,EUR,COMPLETED,100.00
        CARD_PAYMENT,Current,2025-01-03 12:30:00,2025-01-04 09:00:00,Coffee shop,-3.50,0.00,EUR,COMPLETED,96.50
        """;
    MockMultipartFile file = new MockMultipartFile("multipartFile", "revolut.csv", "text/csv", csv.getBytes());
    mvc.perform(multipart("/api/banks/transactions/{bank}/upload", "Revolut").file(file).header("Authorization", auth))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$", hasSize(2)));
    mvc.perform(post("/api/banks/transactions/accounts/{a}/balance", "Revolut_Current")
            .contentType(MediaType.APPLICATION_JSON).content("{\"amountToday\": 96.50}")
            .header("Authorization", auth))
        .andExpect(status().isOk());
    mvc.perform(get("/api/banks/transactions/accounts/{a}/{t}", "Revolut_Current", "2025-01-02 23:00:00")
            .header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(content().string("100.0000"));

    MockMultipartFile bad = new MockMultipartFile("multipartFile", "x.csv", "text/csv", "not,a\nrevolut".getBytes());
    mvc.perform(multipart("/api/banks/transactions/{bank}/upload", "Revolut").file(bad).header("Authorization", auth))
        .andExpect(status().isBadRequest());
  }
}
