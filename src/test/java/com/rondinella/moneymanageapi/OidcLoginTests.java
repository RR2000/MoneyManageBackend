package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.auth.AuthService;
import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.auth.mail.EmailToken;
import com.rondinella.moneymanageapi.auth.mail.EmailTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * "Entra con raoulrondinella.dev" with AUTH_MODE=both, against a fake OpenID provider on a local port (real HTTP for
 * discovery, keys, token and userinfo; the test plays the browser). Password and GitHub logins stay on.
 */
@SpringBootTest(properties = {
    "app.auth.mode=both", "app.web-url=https://money.example.org",
    "app.github.client-id=test-client", "app.github.client-secret=test-secret",
    "app.auth.max-registrations=10000"})
@AutoConfigureMockMvc
class OidcLoginTests {

  private static final FakeOidcProvider IDP = FakeOidcProvider.get();

  @Autowired
  MockMvc mvc;
  @Autowired
  UserRepository users;
  @Autowired
  AuthService authService;
  @Autowired
  EmailTokenRepository tokens;
  @Autowired
  JdbcTemplate jdbc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.oidc.issuer", IDP::issuer);
    registry.add("app.oidc.client-id", () -> FakeOidcProvider.CLIENT_ID);
  }

  private static String unique() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private static Map<String, Object> person(String sub, String email, boolean verified, String username) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", sub);
    claims.put("email", email);
    claims.put("email_verified", verified);
    claims.put("preferred_username", username);
    return claims;
  }

  /** The whole round trip, as the browser does it; answers the fragment of the redirect back to the web app. */
  private String login(Map<String, Object> claims) throws Exception {
    MvcResult start = mvc.perform(get("/api/oauth2/authorization/zitadel")
            .header("Host", "money.example.org").header("X-Forwarded-Proto", "https"))
        .andExpect(status().is3xxRedirection())
        .andReturn();
    String location = start.getResponse().getHeader("Location");
    assertNotNull(location);
    assertTrue(location.startsWith(IDP.authorizationEndpoint() + "?"), location);
    Map<String, String> request = FakeOidcProvider.parse(URI.create(location).getRawQuery());
    assertEquals("https://money.example.org/api/login/oauth2/code/zitadel", request.get("redirect_uri"));
    String code = IDP.issueCode(request, claims);

    MockHttpSession session = (MockHttpSession) start.getRequest().getSession(false);
    assertNotNull(session, "the authorization request (state, PKCE verifier, nonce) is kept in the session");
    String back = mvc.perform(get("/api/login/oauth2/code/zitadel")
            .param("code", code).param("state", request.get("state"))
            .session(session)
            .header("Host", "money.example.org").header("X-Forwarded-Proto", "https"))
        .andExpect(status().is3xxRedirection())
        .andReturn().getResponse().getHeader("Location");
    assertNotNull(back);
    String path = URI.create(back).getPath();
    assertEquals("/login", path, back);
    return back.substring(back.indexOf('#') + 1);
  }

  private static String token(String fragment) {
    Map<String, String> values = FakeOidcProvider.parse(fragment);
    assertTrue(values.containsKey("token"), "no token: " + fragment.replaceAll("token=[^&]*", "token=..."));
    return values.get("token");
  }

  private String accountName(String jwt) throws Exception {
    return mvc.perform(get("/api/account").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString().replaceAll(".*\"username\"\\s*:\\s*\"([^\"]+)\".*", "$1");
  }

  @Test
  void theWebAppIsToldAllTheWaysAndTheLogout() throws Exception {
    // the provider's configuration is read right after startup, or at the latest at the first login
    login(person("warm-" + unique(), "warm" + unique() + "@example.org", true, "warm"));
    mvc.perform(get("/api/auth/providers"))
        .andExpect(jsonPath("$.password").value(true))
        .andExpect(jsonPath("$.github").value(true))
        .andExpect(jsonPath("$.oidc").value(true))
        .andExpect(jsonPath("$.oidcUrl").value("/api/oauth2/authorization/zitadel"))
        .andExpect(jsonPath("$.oidcLogoutUrl").value(IDP.endSessionEndpoint() + "?client_id="
            + FakeOidcProvider.CLIENT_ID + "&post_logout_redirect_uri=https%3A%2F%2Fmoney.example.org%2F"));
  }

  @Test
  void aNewPersonGetsANewAccountWithItsOwnSchemaThenTheSameOneEveryTime() throws Exception {
    String sub = "sub-" + unique();
    String email = "new" + unique() + "@example.org";
    String fragment = login(person(sub, email, true, email));
    assertTrue(fragment.contains("&id_token="), "the id token comes back for the logout");

    String name = accountName(token(fragment));
    User user = users.findByUsername(name).orElseThrow();
    // the email is the provider's verified one; the username is the part before the @, never the whole email
    assertEquals(email, user.getEmail());
    assertEquals(sub, user.getIdpSubject());
    assertFalse(name.contains("@"));
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?",
        Integer.class, "u" + user.getId()));
    mvc.perform(post("/api/sample-data").header("Authorization", "Bearer " + token(fragment)))
        .andExpect(status().isCreated());

    // next time, even with another email at the provider: the subject decides
    String again = accountName(token(login(person(sub, "changed" + unique() + "@example.org", true, "x"))));
    assertEquals(name, again);
  }

  @Test
  void theCodeIsExchangedAsAPublicClientWithPkce() throws Exception {
    int before = IDP.tokenRequests.size();
    login(person("pkce-" + unique(), "pkce" + unique() + "@example.org", true, "pkce"));
    Map<String, String> request = IDP.tokenRequests.get(before);
    assertEquals(FakeOidcProvider.CLIENT_ID, request.get("client_id"));
    assertNotNull(request.get("code_verifier"));
    assertNull(request.get("client_secret"));
    assertNull(request.get("Authorization"));
  }

  @Test
  void anAccountWithTheSameConfirmedEmailIsLinkedOnlyWithAVerifiedEmail() throws Exception {
    String email = "own" + unique() + "@example.org";
    User existing = authService.createAccount("own" + unique(), "password123", email);
    String sub = "link-" + unique();

    // not verified at the provider: refused, and the account is not linked
    assertEquals("oidc-error=email-unverified", login(person(sub, email, false, "own")));
    assertNull(users.findById(existing.getId()).orElseThrow().getIdpSubject());

    // verified (case and spaces do not matter): the same account, linked
    String name = accountName(token(login(person(sub, " " + email.toUpperCase() + " ", true, "own"))));
    assertEquals(existing.getUsername(), name);
    assertEquals(sub, users.findById(existing.getId()).orElseThrow().getIdpSubject());

    // another subject with the same email cannot take it over
    assertEquals("oidc-error=already-linked", login(person("other-" + unique(), email, true, "own")));
  }

  @Test
  void aPendingConfirmationOfTheEmailIsRefusedWithItsCode() throws Exception {
    String email = "wait" + unique() + "@example.org";
    User waiting = authService.createAccount("wait" + unique(), "password123", null);
    Instant now = Instant.now();
    tokens.save(new EmailToken("h" + UUID.randomUUID().toString().replace("-", ""), waiting.getId(),
        EmailToken.Purpose.VERIFY, email, now, now.plusSeconds(3600)));
    long before = users.count();

    assertEquals("oidc-error=email-unconfirmed", login(person("wait-" + unique(), email, true, "wait")));
    assertEquals(before, users.count());
  }

  @Test
  void withoutAVerifiedEmailNoAccountIsCreated() throws Exception {
    long before = users.count();
    assertEquals("oidc-error=email-unverified",
        login(person("nov-" + unique(), "nov" + unique() + "@example.org", false, "nov")));
    assertEquals(before, users.count());
  }

  @Test
  void aTakenUsernameGetsASuffix() throws Exception {
    String base = "taken" + unique();
    authService.createAccount(base, "password123", null);
    String name = accountName(token(login(person("t-" + unique(), base + "@example.org", true, base + "@example.org"))));
    assertEquals(base + "-id", name);
  }

  @Test
  void passwordLoginStillWorks() throws Exception {
    String name = "pw" + unique();
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name + "\",\"password\":\"password123\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name + "\",\"password\":\"password123\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token", not(emptyString())));
    mvc.perform(get("/api/oauth2/authorization/github").header("Host", "money.example.org").header("X-Forwarded-Proto", "https"))
        .andExpect(header().string("Location", startsWith("https://github.com/login/oauth/authorize")));
  }
}
