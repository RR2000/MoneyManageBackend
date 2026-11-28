package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import com.rondinella.moneymanageapi.demo.SampleData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
    mvc.perform(get("/api/banks/transactions/accounts").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", containsInAnyOrder(SampleData.CHECKING, SampleData.SAVINGS)));
    // the balance given to the sample data
    mvc.perform(get("/api/money-accounts/{id}", AccountPages.idOf(SampleData.CHECKING)).header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(3480.25));
    mvc.perform(get("/api/investments").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions[*].isin", hasItem(SampleData.ETF_ISIN)));
    mvc.perform(get("/api/plans").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.plans", hasSize(1)));
    mvc.perform(get("/api/realestate").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)));
    mvc.perform(get("/api/assets").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totals[0].realEstate").value(168000.0));
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
    mvc.perform(get("/api/money-accounts/{id}/balances", AccountPages.idOf("Revolut_Current"))
            .header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.date == '2025-01-02')].balance", contains(100.0)));

    MockMultipartFile bad = new MockMultipartFile("multipartFile", "x.csv", "text/csv", "not,a\nrevolut".getBytes());
    mvc.perform(multipart("/api/banks/transactions/{bank}/upload", "Revolut").file(bad).header("Authorization", auth))
        .andExpect(status().isBadRequest());
  }
}
