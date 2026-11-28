package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.auth.oidc.AuthMode;
import com.rondinella.moneymanageapi.auth.oidc.OidcLogin;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** AUTH_MODE: legacy (default, the provider off even if configured), oidc (only the provider), and going back. */
class AuthModeTests {

  private static final FakeOidcProvider IDP = FakeOidcProvider.get();

  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  @Test
  void theModeIsOneOfThreeAndOidcNeedsTheProvider() {
    assertEquals(AuthMode.LEGACY, AuthMode.parse(null));
    assertEquals(AuthMode.LEGACY, AuthMode.parse(" "));
    assertEquals(AuthMode.BOTH, AuthMode.parse("Both"));
    assertEquals(AuthMode.OIDC, AuthMode.parse("oidc"));
    assertThrows(IllegalStateException.class, () -> AuthMode.parse("sso"));
    // nobody could log in: the site does not start
    assertThrows(IllegalStateException.class, () -> new OidcLogin("oidc", "", "", ""));
    assertThrows(IllegalStateException.class, () -> new OidcLogin("oidc", "https://auth.example.org", "", ""));
    // both without the provider: the usual logins only
    assertFalse(new OidcLogin("both", "", "", "").enabled());
    assertTrue(new OidcLogin("both", "", "", "").passwordLogin());
  }

  /** The default (prod today): nothing changes, even with the provider configured. */
  @Nested
  @SpringBootTest(properties = {"app.github.client-id=test-client", "app.github.client-secret=test-secret"})
  @AutoConfigureMockMvc
  class Legacy {

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
      database(registry);
      registry.add("app.oidc.issuer", IDP::issuer);
      registry.add("app.oidc.client-id", () -> FakeOidcProvider.CLIENT_ID);
    }

    @Test
    void noProviderButtonAndNoWayToIt() throws Exception {
      mvc.perform(get("/api/auth/providers"))
          .andExpect(jsonPath("$.password").value(true))
          .andExpect(jsonPath("$.github").value(true))
          .andExpect(jsonPath("$.oidc").value(false))
          .andExpect(jsonPath("$.oidcUrl").doesNotExist());
      mvc.perform(get("/api/oauth2/authorization/zitadel").header("Host", "money.example.org"))
          .andExpect(header().string("Location", not(startsWith(IDP.issuer()))));
    }
  }

  /** Only the provider: password login, sign-up, reset and GitHub are off; the accounts are the same. */
  @Nested
  @SpringBootTest(properties = {"app.auth.mode=oidc",
      "app.github.client-id=test-client", "app.github.client-secret=test-secret"})
  @AutoConfigureMockMvc
  class OidcOnly {

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
      database(registry);
      registry.add("app.oidc.issuer", IDP::issuer);
      registry.add("app.oidc.client-id", () -> FakeOidcProvider.CLIENT_ID);
    }

    @Test
    void onlyTheProvider() throws Exception {
      mvc.perform(get("/api/auth/providers"))
          .andExpect(jsonPath("$.password").value(false))
          .andExpect(jsonPath("$.github").value(false))
          .andExpect(jsonPath("$.passwordReset").value(false))
          .andExpect(jsonPath("$.oidc").value(true));
      String body = "{\"username\":\"someone\",\"password\":\"password123\"}";
      mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isNotFound());
      mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isNotFound());
      mvc.perform(post("/api/auth/password/forgot").contentType(MediaType.APPLICATION_JSON)
              .content("{\"email\":\"someone@example.org\"}"))
          .andExpect(status().isNotFound());
      mvc.perform(get("/api/oauth2/authorization/github").header("Host", "money.example.org"))
          .andExpect(header().string("Location", not(startsWith("https://github.com"))));
      mvc.perform(get("/api/oauth2/authorization/zitadel").header("Host", "money.example.org"))
          .andExpect(header().string("Location", startsWith(IDP.authorizationEndpoint())));
    }
  }

  /** The provider is down: its button leads back to the login page with a code, the other logins still work. */
  @Nested
  @SpringBootTest(properties = {"app.auth.mode=both", "app.oidc.issuer=http://127.0.0.1:9",
      "app.oidc.client-id=x", "app.auth.max-registrations=10000"})
  @AutoConfigureMockMvc
  class ProviderDown {

    @Autowired
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
      database(registry);
    }

    @Test
    void backToTheLoginPageWithACode() throws Exception {
      mvc.perform(get("/api/oauth2/authorization/zitadel").header("Host", "money.example.org"))
          .andExpect(status().is3xxRedirection())
          .andExpect(header().string("Location", endsWith("/login#oidc-error=unavailable")));
      mvc.perform(get("/api/auth/providers"))
          .andExpect(jsonPath("$.oidc").value(true))
          .andExpect(jsonPath("$.oidcLogoutUrl").doesNotExist());
      String body = "{\"username\":\"down" + System.nanoTime() % 100000 + "\",\"password\":\"password123\"}";
      mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk());
    }
  }
}
