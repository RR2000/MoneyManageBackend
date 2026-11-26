package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.demo.SampleData;
import com.rondinella.moneymanageapi.stocks.Stock;
import com.rondinella.moneymanageapi.stocks.YahooPrices;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The investments graph with Yahoo mocked: asked once, never holding the page, never breaking the graph. */
@SpringBootTest(properties = {"app.auth.max-registrations=10000", "app.prices.wait-ms=500"})
@AutoConfigureMockMvc
class PricesTests {

  private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
  private static final String GRAPH = "/api/brokers/transactions/worth/graph";

  @Autowired
  MockMvc mvc;
  @MockitoBean
  YahooPrices yahoo;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
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

  private static String isin() {
    return "IE" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
  }

  /** Bought 10 two years ago. */
  private void buy(String auth, String isin) throws Exception {
    String date = LocalDate.now().minusYears(2).format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    String csv = "Data,Ora,Prodotto,ISIN,Quantità,Quotazione,Commissioni di\n"
        + date + ",10:00,ETF," + isin + ",10,50,2\n";
    mvc.perform(post("/api/brokers/transactions/upload").header("Authorization", auth)
            .contentType("text/csv;charset=UTF-8").content(csv.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isCreated());
  }

  /** A price of 60 every week for three years, up to last week. */
  private static YahooPrices.Prices weekly(String isin) {
    List<Stock> prices = new ArrayList<>();
    for (LocalDate day = LocalDate.now().minusYears(3); day.isBefore(LocalDate.now().minusDays(6)); day = day.plusWeeks(1)) {
      prices.add(new Stock("TEST.MI", isin, Timestamp.valueOf(day.atStartOfDay()), null, null, null,
          new BigDecimal("60"), Currency.getInstance("EUR"), null));
    }
    return new YahooPrices.Prices("TEST.MI", Currency.getInstance("EUR"), prices);
  }

  @Test
  void pricesAreDownloadedOnceThenReadFromTheDatabase() throws Exception {
    String auth = register(false);
    String isin = isin();
    buy(auth, isin);
    when(yahoo.weekly(isin)).thenReturn(weekly(isin));

    mvc.perform(get(GRAPH).header("Authorization", auth)).andExpect(status().isOk())
        .andExpect(jsonPath("$.uniqueGraphNames", hasItem(isin)))
        // 10 held, at 60
        .andExpect(jsonPath("$.graphData['" + isin + "'].*", everyItem(comparesEqualTo(600.0))));
    mvc.perform(get(GRAPH).header("Authorization", auth)).andExpect(status().isOk())
        .andExpect(jsonPath("$.uniqueGraphNames", hasItem(isin)));
    verify(yahoo, times(1)).weekly(anyString());
  }

  @Test
  void anIsinYahooDoesNotKnowLeavesTheRestOfTheGraphAndIsNotAskedAgainAtOnce() throws Exception {
    String auth = register(true);
    String isin = isin();
    buy(auth, isin);
    when(yahoo.weekly(isin)).thenThrow(new IOException("Yahoo Finance: HTTP 429"));

    for (int i = 0; i < 3; i++) {
      mvc.perform(get(GRAPH).header("Authorization", auth)).andExpect(status().isOk())
          .andExpect(jsonPath("$.uniqueGraphNames", hasItem(SampleData.ETF_ISIN)))
          .andExpect(jsonPath("$.uniqueGraphNames", not(hasItem(isin))));
    }
    verify(yahoo, times(1)).weekly(isin);
    // the made-up ISIN of the sample data is never asked
    verify(yahoo, never()).weekly(SampleData.ETF_ISIN);
  }

  @Test
  void aSlowYahooDoesNotHoldThePageAndThePricesArriveAfterwards() throws Exception {
    String auth = register(false);
    String isin = isin();
    buy(auth, isin);
    when(yahoo.weekly(isin)).thenAnswer(call -> {
      Thread.sleep(1500);
      return weekly(isin);
    });

    long start = System.currentTimeMillis();
    mvc.perform(get(GRAPH).header("Authorization", auth)).andExpect(status().isOk())
        .andExpect(jsonPath("$.uniqueGraphNames", not(hasItem(isin))));
    long took = System.currentTimeMillis() - start;
    assertTrue(took < 1400, "the graph waited " + took + " ms");

    // the download went on in the background, into the schema of this user
    Thread.sleep(2500);
    mvc.perform(get(GRAPH).header("Authorization", auth)).andExpect(status().isOk())
        .andExpect(jsonPath("$.uniqueGraphNames", hasItem(isin)));
    verify(yahoo, times(1)).weekly(isin);
  }
}
