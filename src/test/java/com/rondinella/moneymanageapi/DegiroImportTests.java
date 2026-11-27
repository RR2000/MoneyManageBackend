package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.brokertransactions.DegiroFiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Degiro files imported twice, in two languages, over rows imported before the order id: never doubled. */
@SpringBootTest(properties = "app.auth.max-registrations=10000")
@AutoConfigureMockMvc
class DegiroImportTests {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired
  MockMvc mvc;
  @Autowired
  JdbcTemplate jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  private record Account(String auth, String schema) {
  }

  private Account register() throws Exception {
    String name = "d" + UUID.randomUUID().toString().substring(0, 8);
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name + "\",\"password\":\"password123\"}"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    long id = jdbc.queryForObject("SELECT id FROM public.users WHERE username = ?", Long.class, name);
    return new Account("Bearer " + JSON.readTree(json).get("token").asString(), "u" + id);
  }

  private void trades(Account a, String csv) throws Exception {
    mvc.perform(post("/api/brokers/transactions/upload").header("Authorization", a.auth())
            .contentType("text/csv;charset=UTF-8").content(csv.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isCreated());
  }

  private void account(Account a, String csv) throws Exception {
    mvc.perform(multipart("/api/banks/transactions/{bank}/upload", "Degiro")
            .file(new MockMultipartFile("multipartFile", "Account.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", a.auth()))
        .andExpect(status().isCreated());
  }

  private int count(Account a, String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + a.schema() + "." + table, Integer.class);
  }

  @Test
  void theSameExecutionsTwiceAndInEnglishAreStoredOnceWithTheirCurrencyAndOrder() throws Exception {
    Account a = register();
    trades(a, DegiroFiles.TRANSACTIONS_IT);
    assertEquals(3, count(a, "broker_transaction"), "two fills of the same order in the same minute are two rows");
    trades(a, DegiroFiles.TRANSACTIONS_IT);
    trades(a, DegiroFiles.TRANSACTIONS_EN);
    assertEquals(3, count(a, "broker_transaction"));
    assertEquals(List.of("EUR", "USD", "USD"), jdbc.queryForList("SELECT currency FROM " + a.schema()
        + ".broker_transaction ORDER BY datetime, id", String.class));
    assertEquals(List.of("EUR"), jdbc.queryForList("SELECT DISTINCT fee_currency FROM " + a.schema()
        + ".broker_transaction", String.class));
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM " + a.schema() + ".broker_transaction WHERE order_id = ?",
        Integer.class, DegiroFiles.ORDER_2));
  }

  @Test
  void rowsImportedBeforeTheOrderIdGetItInsteadOfADuplicate() throws Exception {
    Account a = register();
    // as the old import left them: no currency, no order id, the two equal fills as one row (the old key)
    jdbc.update("INSERT INTO " + a.schema() + ".broker_transaction (datetime, description, isin, quantity, stock_price, fee) "
        + "VALUES ('2026-11-03 10:15', 'ETF FINTO MONDO', 'IE0000000011', 5, 80.12, 2), "
        + "('2026-11-04 15:30', 'AZIONE FINTA USA', 'US0000000022', 2, 150.50, 0.5)");
    trades(a, DegiroFiles.TRANSACTIONS_IT);
    assertEquals(3, count(a, "broker_transaction"), "the old rows reused, only the fill the old key had merged is added");
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + a.schema()
        + ".broker_transaction WHERE order_id IS NULL OR currency IS NULL", Integer.class));
    trades(a, DegiroFiles.TRANSACTIONS_EN);
    assertEquals(3, count(a, "broker_transaction"));
  }

  @Test
  void theAccountStatementKeepsUsdDividendsInUsdAndIsNotDoubled() throws Exception {
    Account a = register();
    // a dividend imported before as EUR (the old import wrote EUR on every row)
    jdbc.update("INSERT INTO " + a.schema() + ".bank_transaction (account, datetime, description, amount, fee, currency) "
        + "VALUES ('Degiro', '2026-11-04 07:40', 'Dividendo', 0.48, 0, 'EUR')");
    account(a, DegiroFiles.ACCOUNT_IT);
    account(a, DegiroFiles.ACCOUNT_IT);
    assertEquals(4, count(a, "bank_transaction"));
    assertEquals("USD", jdbc.queryForObject("SELECT currency FROM " + a.schema()
        + ".bank_transaction WHERE description = 'Dividendo'", String.class), "corrected, not converted");
    assertEquals("-0.0700", jdbc.queryForObject("SELECT amount::text FROM " + a.schema()
        + ".bank_transaction WHERE description = 'Ritenuta sul dividendo' AND currency = 'USD'", String.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + a.schema()
        + ".bank_transaction WHERE description LIKE '%Sweep%' OR description = 'Cambio valuta'", Integer.class));

    account(a, DegiroFiles.ACCOUNT_EN);
    assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM " + a.schema()
        + ".bank_transaction WHERE currency = 'USD' AND description LIKE 'Dividend%' AND description NOT LIKE 'Dividendo'",
        Integer.class));
  }

  @Test
  void theMigrationKeepsEveryExecutionAndGivesEachAnId() {
    String schema = "migb" + UUID.randomUUID().toString().substring(0, 8);
    org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema)
        .locations("classpath:db/tenant").target("4").load().migrate();
    jdbc.update("INSERT INTO " + schema + ".broker_transaction (datetime, description, isin, quantity, stock_price, fee) "
        + "VALUES ('2025-01-01 10:00', 'x', 'IE0000000011', 1, 10, 1), ('2025-01-01 10:00', 'x', 'IE0000000011', 2, 10, 1)");
    assertThrows(Exception.class, () -> jdbc.update("INSERT INTO " + schema
        + ".broker_transaction (datetime, isin, quantity, stock_price) VALUES ('2025-01-01 10:00', 'IE0000000011', 1, 10)"));
    org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema)
        .locations("classpath:db/tenant").load().migrate();
    assertEquals(2, jdbc.queryForObject("SELECT count(DISTINCT id) FROM " + schema + ".broker_transaction", Integer.class));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema
        + ".broker_transaction WHERE currency IS NOT NULL OR order_id IS NOT NULL", Integer.class));
    jdbc.update("INSERT INTO " + schema + ".broker_transaction (datetime, isin, quantity, stock_price) "
        + "VALUES ('2025-01-01 10:00', 'IE0000000011', 1, 10)");
    assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".broker_transaction", Integer.class));
    jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
  }

  @Test
  void aFileThatIsNotDegiroIsRefused() throws Exception {
    Account a = register();
    mvc.perform(post("/api/brokers/transactions/upload").header("Authorization", a.auth()).contentType("text/csv")
        .content(DegiroFiles.ACCOUNT_IT)).andExpect(status().isBadRequest());
    mvc.perform(multipart("/api/banks/transactions/{bank}/upload", "Degiro")
            .file(new MockMultipartFile("multipartFile", "x.csv", "text/csv", "a,b\n1,2\n".getBytes()))
            .header("Authorization", a.auth()))
        .andExpect(status().isBadRequest());
  }
}
