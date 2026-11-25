package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.demo.SampleData;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** What a public site with real money data needs: users never see each other, guessing is slowed, errors say little. */
@SpringBootTest(properties = "app.auth.max-registrations=10000")
@AutoConfigureMockMvc
class SecurityTests {

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

  private String register(String username, boolean sampleData) throws Exception {
    String body = "{\"username\":\"" + username + "\",\"password\":\"password123\",\"sampleData\":" + sampleData + "}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    Matcher m = TOKEN.matcher(json);
    if (!m.find()) throw new AssertionError("no token");
    return "Bearer " + m.group(1);
  }

  private static String user() {
    return "s" + UUID.randomUUID().toString().substring(0, 8);
  }

  private String body(MockHttpServletRequestBuilder request, String auth) throws Exception {
    return mvc.perform(request.header("Authorization", auth))
        .andExpect(status().is2xxSuccessful())
        .andReturn().getResponse().getContentAsString();
  }

  /** Everything alice can read, endpoint by endpoint (no market data calls). */
  private List<String> everything(String auth) throws Exception {
    LocalDate today = LocalDate.now();
    String from = today.minusYears(2) + " 00:00:00.000";
    String to = today + " 23:59:59.999";
    List<String> all = new ArrayList<>();
    all.add(body(get("/api/banks/transactions"), auth));
    all.add(body(get("/api/banks/transactions/accounts"), auth));
    for (String account : List.of(SampleData.CHECKING, SampleData.SAVINGS)) {
      all.add(body(get("/api/banks/transactions/accounts/{a}", account), auth));
      all.add(body(get("/api/banks/transactions/accounts/{a}/{t}", account, today + " 00:00:00.000"), auth));
      all.add(body(get("/api/banks/transactions/table/{f}/{t}", from, to).param("accountName", account), auth));
      all.add(body(get("/api/banks/transactions/daily-balance/{a}", account), auth));
    }
    all.add(body(get("/api/banks/transactions/graph/{f}/{t}", from, to), auth));
    all.add(body(get("/api/brokers/transactions/worth/graph"), auth));
    all.add(body(get("/api/montecarlo").param("years", "1").param("simulations", "200"), auth));
    all.add(body(get("/api/realestate"), auth));
    all.add(body(get("/api/realestate/worth"), auth));
    return all;
  }

  @Test
  void twoUsersNeverSeeEachOtherOnAnyEndpoint() throws Exception {
    String alice = register(user(), true);
    String bob = register(user(), false);
    List<String> aliceBefore = everything(alice);
    String aliceFlat = JSON.readTree(body(get("/api/realestate"), alice)).get(0).get("id").asString();

    // bob, empty, reads alice's account names and ids: nothing
    LocalDate today = LocalDate.now();
    mvc.perform(get("/api/banks/transactions").header("Authorization", bob)).andExpect(jsonPath("$", hasSize(0)));
    mvc.perform(get("/api/banks/transactions/accounts").header("Authorization", bob)).andExpect(jsonPath("$", hasSize(0)));
    mvc.perform(get("/api/banks/transactions/accounts/{a}", SampleData.CHECKING).header("Authorization", bob))
        .andExpect(jsonPath("$", hasSize(0)));
    mvc.perform(get("/api/banks/transactions/accounts/{a}/{t}", SampleData.CHECKING, today + " 00:00:00.000")
        .header("Authorization", bob)).andExpect(content().string("0"));
    mvc.perform(get("/api/banks/transactions/table/{f}/{t}", today.minusYears(2) + " 00:00:00.000", today + " 23:59:59.999")
        .param("accountName", SampleData.CHECKING).header("Authorization", bob)).andExpect(jsonPath("$", hasSize(0)));
    mvc.perform(get("/api/banks/transactions/daily-balance/{a}", SampleData.CHECKING).header("Authorization", bob))
        .andExpect(jsonPath("$", hasSize(0)));
    mvc.perform(get("/api/banks/transactions/graph/{f}/{t}", today.minusYears(2) + " 00:00:00.000", today + " 23:59:59.999")
        .header("Authorization", bob)).andExpect(status().isOk())
        .andExpect(jsonPath("$.uniqueGraphNames", not(hasItem(SampleData.CHECKING))));
    mvc.perform(get("/api/brokers/transactions/worth/graph").header("Authorization", bob))
        .andExpect(jsonPath("$.uniqueGraphNames", not(hasItem(SampleData.ETF_ISIN))));
    mvc.perform(get("/api/montecarlo").header("Authorization", bob))
        .andExpect(status().isOk()).andExpect(jsonPath("$.uniqueGraphNames", anyOf(nullValue(), hasSize(0))));
    mvc.perform(get("/api/realestate").header("Authorization", bob)).andExpect(jsonPath("$", hasSize(0)));
    mvc.perform(get("/api/realestate/worth").header("Authorization", bob)).andExpect(content().string("0"));
    mvc.perform(get("/api/realestate/{id}", aliceFlat).header("Authorization", bob)).andExpect(status().isNotFound());

    // bob writes with alice's names and ids: only his schema changes
    mvc.perform(put("/api/realestate/{id}", aliceFlat).header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Mine now\",\"currentEstimatedValue\":1}")).andExpect(status().isNotFound());
    mvc.perform(delete("/api/realestate/{id}", aliceFlat).header("Authorization", bob));
    mvc.perform(post("/api/banks/transactions/accounts/{a}/balance", SampleData.CHECKING).header("Authorization", bob)
        .contentType(MediaType.APPLICATION_JSON).content("{\"amountToday\": 1}")).andExpect(content().string("false"));
    mvc.perform(post("/api/banks/transactions").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
        .content("[{\"account\":\"" + SampleData.CHECKING + "\",\"datetime\":\"2025-01-02T10:00:00.000+00:00\","
            + "\"description\":\"Bob\",\"amount\":5,\"fee\":0,\"currency\":\"EUR\"}]"))
        .andExpect(status().isCreated());
    String csv = """
        Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance
        TOPUP,Current,2025-01-02 10:00:00,2025-01-02 10:00:01,Top-up,100.00,0.00,EUR,COMPLETED,100.00
        """;
    mvc.perform(multipart("/api/banks/transactions/{bank}/upload", "Revolut")
        .file(new MockMultipartFile("multipartFile", "r.csv", "text/csv", csv.getBytes())).header("Authorization", bob))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/brokers/transactions/upload").header("Authorization", bob).contentType("text/csv")
        .content("Data,Ora,Prodotto,ISIN,Borsa,Luogo di esecuzione,Quantita,Quotazione,,Valore locale,,Valore,,Tasso di cambio,Costi di transazione,,Totale,,ID Ordine\n"));
    mvc.perform(post("/api/sample-data").header("Authorization", bob)).andExpect(status().isConflict());
    mvc.perform(post("/api/realestate").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Box\",\"currentEstimatedValue\":20000,\"currency\":\"EUR\"}")).andExpect(status().isCreated());

    // alice: exactly what she had
    assertEquals(aliceBefore, everything(alice));
    // bob: only his own
    mvc.perform(get("/api/banks/transactions/accounts").header("Authorization", bob))
        .andExpect(jsonPath("$", containsInAnyOrder(SampleData.CHECKING, "Revolut_Current")));
    mvc.perform(get("/api/banks/transactions/accounts/{a}", SampleData.CHECKING).header("Authorization", bob))
        .andExpect(jsonPath("$", hasSize(1)));
  }

  @Test
  void sampleDataOnRequestFillsOnlyTheCaller() throws Exception {
    String alice = register(user(), false);
    String bob = register(user(), false);
    mvc.perform(post("/api/sample-data").header("Authorization", bob)).andExpect(status().isCreated());
    mvc.perform(post("/api/sample-data").header("Authorization", bob)).andExpect(status().isConflict());
    mvc.perform(get("/api/banks/transactions").header("Authorization", alice)).andExpect(jsonPath("$", hasSize(0)));
    mvc.perform(get("/api/realestate").header("Authorization", alice)).andExpect(jsonPath("$", hasSize(0)));
  }

  @Test
  void usersInParallelStillSeeOnlyTheirOwn() throws Exception {
    String alice = register(user(), true);
    String bob = register(user(), false);
    mvc.perform(post("/api/realestate").header("Authorization", bob).contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Bob only\",\"currentEstimatedValue\":1,\"currency\":\"EUR\"}")).andExpect(status().isCreated());
    String aliceAccounts = body(get("/api/banks/transactions/accounts"), alice);
    String aliceFlats = body(get("/api/realestate"), alice);
    String bobAccounts = body(get("/api/banks/transactions/accounts"), bob);
    String bobFlats = body(get("/api/realestate"), bob);

    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> runs = new ArrayList<>();
      for (int i = 0; i < 80; i++) {
        boolean a = i % 2 == 0;
        runs.add(pool.submit(() -> {
          assertEquals(a ? aliceAccounts : bobAccounts, body(get("/api/banks/transactions/accounts"), a ? alice : bob));
          assertEquals(a ? aliceFlats : bobFlats, body(get("/api/realestate"), a ? alice : bob));
          return null;
        }));
      }
      for (Future<?> run : runs) run.get();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void forgedOrExpiredTokensAreRefused() throws Exception {
    String name = user();
    String good = register(name, false).substring("Bearer ".length());
    String[] parts = good.split("\\.");
    String otherKey = Jwts.builder().subject(name).expiration(new Date(System.currentTimeMillis() + 60_000))
        .signWith(Jwts.SIG.HS256.key().build()).compact();
    String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
        + "." + parts[1] + ".";
    String tamperedPayload = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(("{\"sub\":\"" + name + "x\"}").getBytes()) + "." + parts[2];
    for (String token : List.of(otherKey, none, tamperedPayload, "garbage")) {
      mvc.perform(get("/api/banks/transactions/accounts").header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(get("/api/banks/transactions/accounts").header("Authorization", "Bearer " + good))
        .andExpect(status().isOk());
  }

  @Test
  void wrongPasswordsLockTheUsernameForAWhileExistingOrNot() throws Exception {
    String name = user();
    register(name, false);
    String wrong = "{\"username\":\"" + name + "\",\"password\":\"wrong-password\"}";
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong))
          .andExpect(status().isUnauthorized());
    }
    // locked: even the right password waits
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name + "\",\"password\":\"password123\"}"))
        .andExpect(status().isTooManyRequests());

    // a username that does not exist answers exactly the same way
    String ghost = "{\"username\":\"" + user() + "\",\"password\":\"wrong-password\"}";
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(ghost))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(ghost))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void heavyRequestsAreBounded() throws Exception {
    String auth = register(user(), false);
    mvc.perform(get("/api/montecarlo").param("simulations", "100000000").header("Authorization", auth))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/montecarlo").param("years", "1000").header("Authorization", auth))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/banks/transactions/graph/{f}/{t}", "0001-01-01 00:00:00.000", "9999-12-31 00:00:00.000")
        .header("Authorization", auth)).andExpect(status().isBadRequest());
    mvc.perform(get("/api/brokers/transactions/worth/graph").param("from", "2030-01-01 00:00:00.000")
        .param("to", "2020-01-01 00:00:00.000").header("Authorization", auth)).andExpect(status().isBadRequest());
    // upload size: spring.servlet.multipart.* (MockMvc does not parse multipart: checked on the running app)
  }

  @Test
  void errorsDoNotEchoTheData() throws Exception {
    String auth = register(user(), false);
    mvc.perform(get("/api/realestate/{id}", 424242).header("Authorization", auth))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Not found"))
        .andExpect(content().string(not(containsString("424242"))));
    mvc.perform(post("/api/brokers/transactions/upload").header("Authorization", auth).contentType("text/csv")
            .content("a,b\nSECRET-ROW-31337,1\n"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(not(containsString("31337"))))
        .andExpect(content().string(not(containsString("Exception"))));
    mvc.perform(post("/api/banks/transactions/accounts/{a}/balance", "x").header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    // the old way, with the amount in the URL, is gone
    mvc.perform(get("/api/banks/transactions/accounts/{a}/computeCumulativeAmount/{v}", "x", "123.45")
        .header("Authorization", auth)).andExpect(status().is4xxClientError());
  }

  @Test
  void noCorsNoDocsNoManagementDetails() throws Exception {
    mvc.perform(options("/api/auth/login").header("Origin", "https://evil.example")
            .header("Access-Control-Request-Method", "POST"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    mvc.perform(options("/api/auth/login").header("Origin", "http://localhost:4200")
            .header("Access-Control-Request-Method", "POST"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    mvc.perform(get("/v3/api-docs")).andExpect(status().is4xxClientError());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.components").doesNotExist());
    mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    mvc.perform(get("/actuator/heapdump")).andExpect(status().is4xxClientError());
  }
}
