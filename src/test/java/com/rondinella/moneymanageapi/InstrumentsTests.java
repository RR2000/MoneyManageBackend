package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.stocks.AlphaVantage;
import com.rondinella.moneymanageapi.stocks.OpenFigi;
import com.rondinella.moneymanageapi.stocks.Stock;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Prices from Alpha Vantage (symbol found through OpenFIGI), set by hand or imported, with the services mocked. */
@SpringBootTest(properties = {"app.auth.max-registrations=10000", "app.prices.wait-ms=3000"})
@AutoConfigureMockMvc
class InstrumentsTests {

  private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
  private static final String GRAPH = "/api/brokers/transactions/worth/graph";
  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Currency USD = Currency.getInstance("USD");

  @Autowired
  MockMvc mvc;
  @MockitoBean
  AlphaVantage alphaVantage;
  @MockitoBean
  OpenFigi openFigi;
  @MockitoBean
  YahooPrices yahoo;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  @BeforeEach
  void alphaVantageOn() {
    when(alphaVantage.enabled()).thenReturn(true);
    when(alphaVantage.leftToday()).thenReturn(20);
  }

  private String register() throws Exception {
    String body = "{\"username\":\"i" + UUID.randomUUID().toString().substring(0, 8) + "\",\"password\":\"password123\"}";
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    Matcher m = TOKEN.matcher(json);
    assertTrue(m.find());
    return "Bearer " + m.group(1);
  }

  private static String isin() {
    return "IE" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
  }

  private void buy(String auth, String isin) throws Exception {
    String date = LocalDate.now().minusYears(2).format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    String csv = "Data,Ora,Prodotto,ISIN,Quantità,Quotazione,Commissioni di\n" + date + ",10:00,MSCI WORLD," + isin + ",10,50,2\n";
    mvc.perform(post("/api/brokers/transactions/upload").header("Authorization", auth)
            .contentType("text/csv;charset=UTF-8").content(csv.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isCreated());
  }

  /** A price every week for three years up to a few days ago. */
  private static List<Stock> weekly(String symbol, String isin, String price, Currency currency) {
    List<Stock> prices = new ArrayList<>();
    for (LocalDate day = LocalDate.now().minusYears(3); day.isBefore(LocalDate.now().minusDays(2)); day = day.plusWeeks(1)) {
      prices.add(new Stock(symbol, isin, Timestamp.valueOf(day.atStartOfDay()), null, null, null,
          new BigDecimal(price), currency, null));
    }
    return prices;
  }

  private void listedInLondonAndAmsterdam(String isin) throws IOException {
    when(openFigi.listings(isin)).thenReturn(List.of(
        new OpenFigi.Listing("IWDA", "LN", "ISHARES CORE MSCI WORLD"),
        new OpenFigi.Listing("SWDA", "LN", "ISHARES CORE MSCI WORLD"),
        new OpenFigi.Listing("IWDA", "NA", "ISHARES CORE MSCI WORLD")));
    when(alphaVantage.search("IWDA")).thenReturn(List.of(
        new AlphaVantage.Match("IWDA.LON", "iShares Core MSCI World", "United Kingdom", USD),
        new AlphaVantage.Match("IWDA.AMS", "iShares Core MSCI World", "Amsterdam", EUR)));
    when(alphaVantage.weekly(eq("IWDA.AMS"), eq(isin), any())).thenReturn(weekly("IWDA.AMS", isin, "100", EUR));
  }

  @Test
  void theSymbolIsFoundOnceAndTheEuroListingIsPreferred() throws Exception {
    String auth = register();
    String isin = isin();
    buy(auth, isin);
    listedInLondonAndAmsterdam(isin);

    mvc.perform(get(GRAPH).header("Authorization", auth)).andExpect(status().isOk())
        .andExpect(jsonPath("$.uniqueGraphNames", hasItem(isin)))
        .andExpect(jsonPath("$.graphData['" + isin + "'].*", everyItem(comparesEqualTo(1000.0))));
    mvc.perform(get("/api/instruments").header("Authorization", auth)).andExpect(status().isOk())
        .andExpect(jsonPath("$.alphaVantage").value(true))
        .andExpect(jsonPath("$.instruments[0].isin").value(isin))
        .andExpect(jsonPath("$.instruments[0].symbol").value("IWDA.AMS"))
        .andExpect(jsonPath("$.instruments[0].quoteCurrency").value("EUR"))
        .andExpect(jsonPath("$.instruments[0].currencyWarning").value(false))
        .andExpect(jsonPath("$.instruments[0].source").value("alphavantage"))
        .andExpect(jsonPath("$.instruments[0].lastPrice").value(100.0));
    mvc.perform(get(GRAPH).header("Authorization", auth)).andExpect(status().isOk());
    verify(alphaVantage, times(1)).search(anyString());
    verify(alphaVantage, times(1)).weekly(anyString(), anyString(), any());
    verify(yahoo, never()).weekly(anyString());

    // another user with the same ISIN: OpenFIGI is not asked again (its answers are kept for everybody)
    String other = register();
    buy(other, isin);
    mvc.perform(get(GRAPH).header("Authorization", other)).andExpect(jsonPath("$.uniqueGraphNames", hasItem(isin)));
    verify(openFigi, times(1)).listings(isin);
  }

  @Test
  void withoutAlphaVantagePricesYahooIsTheLastResort() throws Exception {
    String auth = register();
    String isin = isin();
    buy(auth, isin);
    when(openFigi.listings(isin)).thenReturn(List.of(new OpenFigi.Listing("ABCD", "LN", "SOMETHING")));
    when(alphaVantage.search("ABCD")).thenThrow(new IOException("Alpha Vantage: daily limit reached"));
    when(yahoo.weekly(isin)).thenReturn(new YahooPrices.Prices("ABCD.L", EUR, weekly("ABCD.L", isin, "7", EUR)));

    mvc.perform(get(GRAPH).header("Authorization", auth)).andExpect(status().isOk())
        .andExpect(jsonPath("$.uniqueGraphNames", hasItem(isin)));
    mvc.perform(get("/api/instruments").header("Authorization", auth))
        .andExpect(jsonPath("$.instruments[0].source").value("yahoo"));
    verify(yahoo, times(1)).weekly(isin);
  }

  @Test
  void aSymbolSetByHandReplacesThePricesAndWarnsAboutTheCurrency() throws Exception {
    String auth = register();
    String isin = isin();
    buy(auth, isin);
    listedInLondonAndAmsterdam(isin);
    mvc.perform(get(GRAPH).header("Authorization", auth)).andExpect(jsonPath("$.uniqueGraphNames", hasItem(isin)));
    when(alphaVantage.weekly(eq("IWDA.LON"), anyString(), any())).thenReturn(weekly("IWDA.LON", "", "110", USD));
    when(alphaVantage.search("IWDA.LON")).thenReturn(List.of(
        new AlphaVantage.Match("IWDA.LON", "iShares Core MSCI World", "United Kingdom", USD)));

    mvc.perform(get("/api/instruments/preview").param("symbol", "iwda.lon").header("Authorization", auth))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value("IWDA.LON"))
        .andExpect(jsonPath("$.currency").value("USD"))
        .andExpect(jsonPath("$.currencyWarning").value(true))
        .andExpect(jsonPath("$.lastPrice").value(110.0));
    mvc.perform(put("/api/instruments/{isin}/symbol", isin).header("Authorization", auth)
            .contentType(MediaType.APPLICATION_JSON).content("{\"symbol\":\"IWDA.LON\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.symbol").value("IWDA.LON"))
        .andExpect(jsonPath("$.manual").value(true))
        .andExpect(jsonPath("$.quoteCurrency").value("USD"))
        .andExpect(jsonPath("$.currencyWarning").value(true))
        .andExpect(jsonPath("$.lastPrice").value(110.0));
    // the preview was enough: saving asked nothing more (Alpha Vantage allows 25 requests a day)
    verify(alphaVantage, times(1)).weekly(eq("IWDA.LON"), anyString(), any());
    verify(alphaVantage, times(1)).search("IWDA.LON");
    mvc.perform(get(GRAPH).header("Authorization", auth))
        .andExpect(jsonPath("$.graphData['" + isin + "'].*", everyItem(comparesEqualTo(1100.0))));

    // somebody else's ISIN, or a symbol that is not one
    mvc.perform(put("/api/instruments/{isin}/symbol", "IE0000000000").header("Authorization", auth)
        .contentType(MediaType.APPLICATION_JSON).content("{\"symbol\":\"IWDA.LON\"}")).andExpect(status().isNotFound());
    mvc.perform(get("/api/instruments/preview").param("symbol", "a b'c").header("Authorization", auth))
        .andExpect(status().isBadRequest());
  }

  @Test
  void pricesCanBeImportedFromACsv() throws Exception {
    when(alphaVantage.enabled()).thenReturn(false);
    String auth = register();
    String isin = isin();
    buy(auth, isin);
    String csv = "Data;Prezzo\n" + LocalDate.now().minusMonths(2).withDayOfMonth(1).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        + ";70,5\n" + LocalDate.now().minusMonths(1).withDayOfMonth(1) + ";71\n";
    mvc.perform(post("/api/instruments/{isin}/prices", isin).header("Authorization", auth).contentType("text/csv")
            .content(csv)).andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(2));
    mvc.perform(get("/api/instruments").header("Authorization", auth))
        .andExpect(jsonPath("$.alphaVantage").value(false))
        .andExpect(jsonPath("$.instruments[0].source").value("csv"))
        .andExpect(jsonPath("$.instruments[0].lastPrice").value(71.0))
        .andExpect(jsonPath("$.instruments[0].quoteCurrency").value("EUR"));
    mvc.perform(get(GRAPH).header("Authorization", auth))
        .andExpect(jsonPath("$.graphData['" + isin + "'].*", hasItems(comparesEqualTo(705.0), comparesEqualTo(710.0))));

    mvc.perform(post("/api/instruments/{isin}/prices", isin).header("Authorization", auth).contentType("text/csv")
            .content("2024-01-01,10\nnot a line\n"))
        .andExpect(status().isBadRequest()).andExpect(status().reason(containsString("Riga 2")));
    mvc.perform(get("/api/instruments/preview").param("symbol", "IWDA.AMS").header("Authorization", auth))
        .andExpect(status().isConflict());
    verify(alphaVantage, never()).search(anyString());
  }
}
