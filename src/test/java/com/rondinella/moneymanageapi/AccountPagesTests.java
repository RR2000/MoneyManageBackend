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

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The page of one account: detail, balances, movements with search, filter and "load more". Made-up data only. */
@SpringBootTest(properties = "app.auth.max-registrations=10000")
@AutoConfigureMockMvc
class AccountPagesTests {

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
    String body = "{\"username\":\"u" + UUID.randomUUID().toString().substring(0, 8)
        + "\",\"password\":\"password123\",\"sampleData\":" + sampleData + "}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    Matcher m = TOKEN.matcher(json);
    assertTrue(m.find());
    return "Bearer " + m.group(1);
  }

  private JsonNode movements(String auth, String id, String body) throws Exception {
    return JSON.readTree(mvc.perform(post("/api/money-accounts/{id}/movements", id).header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  @Test
  void anAccountHasAPageAddressedByAnIdNotByItsName() throws Exception {
    String auth = register(true);
    String id = AccountPages.idOf(SampleData.CHECKING);
    assertTrue(id.matches("[0-9a-f]{12}"));
    mvc.perform(get("/api/money-accounts").header("Authorization", auth))
        .andExpect(jsonPath("$[?(@.name == '" + SampleData.CHECKING + "')].id", contains(id)));

    mvc.perform(get("/api/money-accounts/{id}", id).header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(SampleData.CHECKING))
        .andExpect(jsonPath("$.currency").value("EUR"))
        .andExpect(jsonPath("$.balance").value(3480.25))
        .andExpect(jsonPath("$.monthOut", lessThanOrEqualTo(0.0)))
        .andExpect(jsonPath("$.linked").value(false));

    mvc.perform(get("/api/money-accounts/{id}/balances", id).param("from", java.time.LocalDate.now().minusMonths(1).toString())
            .header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(greaterThan(3))))
        .andExpect(jsonPath("$[-1:].balance", contains(3480.25)));

    mvc.perform(get("/api/money-accounts/{id}", "000000000000").header("Authorization", auth)).andExpect(status().isNotFound());
    mvc.perform(get("/api/money-accounts/{id}", "not-an-id").header("Authorization", auth)).andExpect(status().isNotFound());
    // another user without that account does not see it
    mvc.perform(get("/api/money-accounts/{id}", id).header("Authorization", register(false))).andExpect(status().isNotFound());
    mvc.perform(get("/api/money-accounts/{id}", id)).andExpect(status().isUnauthorized());
  }

  @Test
  void movementsNewestFirstWithLoadMoreSearchAndFilter() throws Exception {
    String auth = register(true);
    String id = AccountPages.idOf(SampleData.CHECKING);

    JsonNode first = movements(auth, id, "{\"limit\":5}");
    assertEquals(5, first.get("items").size());
    assertTrue(first.get("hasMore").asBoolean());
    String newest = first.get("items").get(0).get("datetime").asString();
    String fifth = first.get("items").get(4).get("datetime").asString();
    assertTrue(newest.compareTo(fifth) >= 0, "newest first");
    assertEquals("MANUAL", first.get("items").get(0).get("source").asString());

    JsonNode next = movements(auth, id, "{\"limit\":5,\"offset\":5}");
    assertNotEquals(first.get("items").get(4).get("id").asLong(), next.get("items").get(0).get("id").asLong());

    JsonNode salary = movements(auth, id, "{\"q\":\"STIPENDIO\",\"limit\":200}");
    assertTrue(salary.get("items").size() >= 12);
    salary.get("items").forEach(m -> assertTrue(m.get("description").asString().startsWith("Stipendio")));

    JsonNode byAmount = movements(auth, id, "{\"q\":\"720,00\",\"limit\":200}");
    assertTrue(byAmount.get("items").size() >= 12);
    byAmount.get("items").forEach(m -> assertEquals(-720.0, m.get("amount").asDouble()));

    JsonNode in = movements(auth, id, "{\"kind\":\"IN\",\"limit\":200}");
    assertFalse(in.get("hasMore").asBoolean());
    in.get("items").forEach(m -> assertTrue(m.get("amount").asDouble() > 0));
    JsonNode out = movements(auth, id, "{\"kind\":\"OUT\",\"limit\":20}");
    out.get("items").forEach(m -> assertTrue(m.get("amount").asDouble() < 0));

    // a % in the search is a character, not a wildcard
    assertEquals(0, movements(auth, id, "{\"q\":\"%\"}").get("items").size());
  }

  @Test
  void amountsTypedInTheSearchBox() {
    assertEquals(0, new java.math.BigDecimal("64.2").compareTo(AccountPages.parseAmountForTest("64,2")));
    assertEquals(0, new java.math.BigDecimal("-64.20").compareTo(AccountPages.parseAmountForTest("−64.20")));
    assertNull(AccountPages.parseAmountForTest("affitto"));
    assertNull(AccountPages.parseAmountForTest("1.234,56"));
  }
}
