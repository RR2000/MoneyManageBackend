package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The demo is off unless MONEY_DEMO_ENABLED=true: no sandbox, and the login page does not offer it. */
@SpringBootTest
@AutoConfigureMockMvc
class DemoOffTests {

  @Autowired
  MockMvc mvc;
  @Autowired
  UserRepository users;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  @Test
  void offByDefault() throws Exception {
    long open = users.countByDemoTrueAndExpiresAtAfter(Instant.now());
    MvcResult r = mvc.perform(post("/api/demo").header("X-Real-IP", "198.51.100.200")).andReturn();
    assertEquals(404, r.getResponse().getStatus());
    assertEquals(open, users.countByDemoTrueAndExpiresAtAfter(Instant.now()), "no sandbox made");
    String providers = mvc.perform(get("/api/auth/providers")).andReturn().getResponse().getContentAsString();
    assertFalse(JsonMapper.builder().build().readTree(providers).get("demo").asBoolean());
  }
}
