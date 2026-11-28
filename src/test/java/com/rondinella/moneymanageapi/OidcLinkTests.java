package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.auth.AuthService;
import com.rondinella.moneymanageapi.auth.JwtService;
import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.auth.oidc.OidcLinks;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * "Collega a raoulrondinella.dev": an account logged in with its password is linked to a person of the OpenID
 * provider (fake, on a local port) whatever the emails; then the provider's login enters that account. The test plays
 * the browser: the settings page asks for a link request, the browser goes to the provider's button with it.
 */
class OidcLinkTests {

  private static final FakeOidcProvider IDP = FakeOidcProvider.get();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String HOST = "money.example.org";

  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
    registry.add("app.oidc.issuer", IDP::issuer);
    registry.add("app.oidc.client-id", () -> FakeOidcProvider.CLIENT_ID);
  }

  static String unique() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  static Map<String, Object> person(String sub, String email, boolean verified) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", sub);
    claims.put("email", email);
    claims.put("email_verified", verified);
    claims.put("preferred_username", "someone");
    return claims;
  }

  /** What the settings page gets from POST /api/account/oidc-link: the URL and the browser's cookie. */
  record LinkRequest(String url, Cookie cookie) {
  }

  /** The browser, from the provider's button to the redirect back to the web app; answers that Location. */
  static String throughProvider(MockMvc mvc, String startUrl, Cookie cookie, Map<String, Object> claims)
      throws Exception {
    var start = get(startUrl).header("Host", HOST).header("X-Forwarded-Proto", "https");
    if (cookie != null) {
      start.cookie(cookie);
    }
    MvcResult started = mvc.perform(start).andExpect(status().is3xxRedirection()).andReturn();
    String location = started.getResponse().getHeader("Location");
    assertNotNull(location);
    if (!location.startsWith(IDP.authorizationEndpoint())) {
      return local(location);
    }
    Map<String, String> request = FakeOidcProvider.parse(URI.create(location).getRawQuery());
    assertFalse(request.containsKey(OidcLinks.PARAMETER), "the link request is not sent to the provider");
    String code = IDP.issueCode(request, claims);
    MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);
    return callback(mvc, session, code, request.get("state"));
  }

  static String callback(MockMvc mvc, MockHttpSession session, String code, String state) throws Exception {
    return local(mvc.perform(get("/api/login/oauth2/code/zitadel")
            .param("code", code).param("state", state)
            .session(session)
            .header("Host", HOST).header("X-Forwarded-Proto", "https"))
        .andExpect(status().is3xxRedirection())
        .andReturn().getResponse().getHeader("Location"));
  }

  /** A redirect within the site, without scheme and host (the servlet container may add them). */
  static String local(String location) {
    String site = "https://" + HOST;
    return location != null && location.startsWith(site + "/") ? location.substring(site.length()) : location;
  }

  static LinkRequest linkRequest(MockMvc mvc, String jwt) throws Exception {
    MvcResult result = mvc.perform(post("/api/account/oidc-link").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andReturn();
    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertNotNull(setCookie);
    // only this site, over HTTPS, not readable by scripts, sent on the top-level navigation to the button
    assertTrue(setCookie.startsWith(OidcLinks.COOKIE + "="), setCookie);
    assertTrue(setCookie.contains("Path=/") && setCookie.contains("Secure") && setCookie.contains("HttpOnly")
        && setCookie.contains("SameSite=Lax") && !setCookie.contains("Domain"), setCookie);
    String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    String url = JSON.readTree(result.getResponse().getContentAsString()).get("url").asString();
    assertTrue(url.startsWith("/api/oauth2/authorization/zitadel?link="), url);
    return new LinkRequest(url, new Cookie(OidcLinks.COOKIE, value));
  }

  static Map<String, Object> account(MockMvc mvc, String jwt) throws Exception {
    String body = mvc.perform(get("/api/account").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    @SuppressWarnings("unchecked")
    Map<String, Object> map = JSON.readValue(body, Map.class);
    return map;
  }

  /** AUTH_MODE=both: password and GitHub as before, plus the provider. */
  @Nested
  @SpringBootTest(properties = {"app.auth.mode=both", "app.web-url=https://money.example.org",
      "app.auth.max-registrations=10000"})
  @AutoConfigureMockMvc
  class Both {

    @Autowired
    MockMvc mvc;
    @Autowired
    UserRepository users;
    @Autowired
    AuthService authService;
    @Autowired
    JwtService jwtService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
      database(registry);
    }

    private User passwordAccount(String email) {
      return authService.createAccount("pw" + unique(), "password123", email);
    }

    private String jwt(User user) {
      return authService.tokenFor(user.getUsername());
    }

    @Test
    void anAccountIsLinkedWhateverTheEmailsThenTheProviderEntersIt() throws Exception {
      User user = passwordAccount("mine" + unique() + "@example.org");
      String sub = "link-" + unique();
      Map<String, Object> before = account(mvc, jwt(user));
      assertEquals(true, before.get("oidc"));
      assertEquals(false, before.get("oidcLinked"));

      // another email at the provider, not even verified: the link does not look at it
      LinkRequest link = linkRequest(mvc, jwt(user));
      String back = throughProvider(mvc, link.url(), link.cookie(),
          person(sub, "other" + unique() + "@example.org", false));
      assertTrue(back.startsWith("/settings#oidc-link=linked&id_token="), back);
      User linked = users.findById(user.getId()).orElseThrow();
      assertEquals(sub, linked.getIdpSubject());
      assertEquals(user.getEmail(), linked.getEmail(), "the account keeps its own email");
      Map<String, Object> after = account(mvc, jwt(user));
      assertEquals(true, after.get("oidcLinked"));
      assertEquals(true, after.get("oidcCanUnlink"));

      // the provider's login now enters this account (another email again: the subject decides)
      String login = throughProvider(mvc, "/api/oauth2/authorization/zitadel", null,
          person(sub, "third" + unique() + "@example.org", true));
      assertTrue(login.startsWith("/login#token="), login);
      String token = FakeOidcProvider.parse(login.substring(login.indexOf('#') + 1)).get("token");
      assertEquals(user.getUsername(), account(mvc, token).get("username"));

      // linked already: no new link request
      mvc.perform(post("/api/account/oidc-link").header("Authorization", "Bearer " + jwt(user)))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error").value("linked"));
    }

    @Test
    void anAccountWithoutAnyEmailIsLinkedToo() throws Exception {
      User user = passwordAccount(null);
      String sub = "noemail-" + unique();
      LinkRequest link = linkRequest(mvc, jwt(user));
      String back = throughProvider(mvc, link.url(), link.cookie(),
          person(sub, "noemail" + unique() + "@example.org", true));
      assertTrue(back.startsWith("/settings#oidc-link=linked"), back);
      User linked = users.findById(user.getId()).orElseThrow();
      assertEquals(sub, linked.getIdpSubject());
      assertNull(linked.getEmail(), "the provider's email is not copied on the account");
    }

    @Test
    void aSubjectOfAnotherAccountIsRefused() throws Exception {
      String sub = "taken-" + unique();
      User owner = passwordAccount(null);
      owner.setIdpSubject(sub);
      users.save(owner);
      User user = passwordAccount(null);

      LinkRequest link = linkRequest(mvc, jwt(user));
      String back = throughProvider(mvc, link.url(), link.cookie(), person(sub, "x" + unique() + "@example.org", true));
      assertEquals("/settings#oidc-link-error=already-linked", back);
      assertNull(users.findById(user.getId()).orElseThrow().getIdpSubject());
      assertEquals(sub, users.findById(owner.getId()).orElseThrow().getIdpSubject());
    }

    @Test
    void anAccountLinkedMeanwhileToAnotherSubjectIsRefused() throws Exception {
      User user = passwordAccount(null);
      LinkRequest link = linkRequest(mvc, jwt(user));
      // linked from another tab while this one is at the provider
      User meanwhile = users.findById(user.getId()).orElseThrow();
      meanwhile.setIdpSubject("first-" + unique());
      users.save(meanwhile);

      String back = throughProvider(mvc, link.url(), link.cookie(), person("second-" + unique(), "y@example.org", true));
      assertEquals("/settings#oidc-link-error=other-subject", back);
      assertEquals(meanwhile.getIdpSubject(), users.findById(user.getId()).orElseThrow().getIdpSubject());
    }

    @Test
    void aLinkRequestWorksOnceOnlyAndOnlyInTheBrowserThatAskedForIt() throws Exception {
      User user = passwordAccount(null);
      LinkRequest link = linkRequest(mvc, jwt(user));

      // another browser (no cookie, or its own): refused, and the request is not used up by it
      assertEquals("/settings#oidc-link-error=expired",
          throughProvider(mvc, link.url(), null, person("a-" + unique(), "a@example.org", true)));
      assertEquals("/settings#oidc-link-error=expired", throughProvider(mvc, link.url(),
          new Cookie(OidcLinks.COOKIE, "someone-else"), person("a-" + unique(), "a@example.org", true)));

      String sub = "once-" + unique();
      assertTrue(throughProvider(mvc, link.url(), link.cookie(), person(sub, "b@example.org", true))
          .startsWith("/settings#oidc-link=linked"));
      // the same request again: refused
      User again = users.findById(user.getId()).orElseThrow();
      again.setIdpSubject(null);
      users.save(again);
      assertEquals("/settings#oidc-link-error=expired",
          throughProvider(mvc, link.url(), link.cookie(), person("c-" + unique(), "c@example.org", true)));
      assertNull(users.findById(user.getId()).orElseThrow().getIdpSubject());
    }

    @Test
    void anExpiredOrForgedLinkRequestIsRefused() throws Exception {
      User user = passwordAccount(null);
      String browser = "browser-" + unique();
      String bnd = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(browser.getBytes(StandardCharsets.UTF_8)));
      Cookie cookie = new Cookie(OidcLinks.COOKIE, browser);
      String expired = jwtService.signForPurpose(OidcLinks.PURPOSE, String.valueOf(user.getId()), unique(),
          Map.of("bnd", bnd), Duration.ofSeconds(-5));
      assertEquals("/settings#oidc-link-error=expired", throughProvider(mvc,
          "/api/oauth2/authorization/zitadel?link=" + expired, cookie, person("e-" + unique(), "e@example.org", true)));
      // a login token is not a link request (another key)
      assertEquals("/settings#oidc-link-error=expired", throughProvider(mvc,
          "/api/oauth2/authorization/zitadel?link=" + jwt(user), cookie, person("f-" + unique(), "f@example.org", true)));
      assertEquals("/settings#oidc-link-error=expired", throughProvider(mvc,
          "/api/oauth2/authorization/zitadel?link=garbage", cookie, person("g-" + unique(), "g@example.org", true)));
      // and a link request is not a login token
      String good = jwtService.signForPurpose(OidcLinks.PURPOSE, String.valueOf(user.getId()), unique(),
          Map.of("bnd", bnd), Duration.ofMinutes(1));
      mvc.perform(get("/api/account").header("Authorization", "Bearer " + good)).andExpect(status().isUnauthorized());
      // GitHub is not linked this way
      assertNull(users.findById(user.getId()).orElseThrow().getIdpSubject());
    }

    @Test
    void theStateWorksOnce() throws Exception {
      User user = passwordAccount(null);
      LinkRequest link = linkRequest(mvc, jwt(user));
      MvcResult started = mvc.perform(get(link.url()).cookie(link.cookie())
              .header("Host", HOST).header("X-Forwarded-Proto", "https"))
          .andExpect(status().is3xxRedirection()).andReturn();
      Map<String, String> request = FakeOidcProvider.parse(
          URI.create(started.getResponse().getHeader("Location")).getRawQuery());
      MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);
      String sub = "state-" + unique();
      String code = IDP.issueCode(request, person(sub, "s@example.org", true));
      assertTrue(callback(mvc, session, code, request.get("state")).startsWith("/settings#oidc-link=linked"));

      // the same state again (another code of the provider): nothing is done
      User unlinked = users.findById(user.getId()).orElseThrow();
      unlinked.setIdpSubject(null);
      users.save(unlinked);
      String replay = IDP.issueCode(request, person("replay-" + unique(), "r@example.org", true));
      assertEquals("/login#oidc-error=failed", callback(mvc, session, replay, request.get("state")));
      assertNull(users.findById(user.getId()).orElseThrow().getIdpSubject());
    }

    @Test
    void notLoggedInNoLinkRequest() throws Exception {
      mvc.perform(post("/api/account/oidc-link")).andExpect(status().isUnauthorized());
      mvc.perform(post("/api/account/oidc-link").header("Authorization", "Bearer not-a-token"))
          .andExpect(status().isUnauthorized());
      mvc.perform(delete("/api/account/oidc-link")).andExpect(status().isUnauthorized());
      // straight to the button with a made-up request: back to the settings page, nothing linked
      long linked = users.findAll().stream().filter(u -> u.getIdpSubject() != null).count();
      assertEquals("/settings#oidc-link-error=expired", throughProvider(mvc,
          "/api/oauth2/authorization/zitadel?link=x", null, person("n-" + unique(), "n@example.org", true)));
      assertEquals(linked, users.findAll().stream().filter(u -> u.getIdpSubject() != null).count());
    }

    @Test
    void unlinkThenTheProviderNoLongerEntersTheAccount() throws Exception {
      User user = passwordAccount(null);
      String sub = "unlink-" + unique();
      LinkRequest link = linkRequest(mvc, jwt(user));
      throughProvider(mvc, link.url(), link.cookie(), person(sub, "u" + unique() + "@example.org", true));
      assertEquals(sub, users.findById(user.getId()).orElseThrow().getIdpSubject());

      mvc.perform(delete("/api/account/oidc-link").header("Authorization", "Bearer " + jwt(user)))
          .andExpect(status().isNoContent());
      assertNull(users.findById(user.getId()).orElseThrow().getIdpSubject());
      assertEquals(false, account(mvc, jwt(user)).get("oidcLinked"));
      // again: nothing to do
      mvc.perform(delete("/api/account/oidc-link").header("Authorization", "Bearer " + jwt(user)))
          .andExpect(status().isNoContent());

      // the provider's login: another account (new, with the provider's verified email), not this one
      String login = throughProvider(mvc, "/api/oauth2/authorization/zitadel", null,
          person(sub, "u" + unique() + "@example.org", true));
      String token = FakeOidcProvider.parse(login.substring(login.indexOf('#') + 1)).get("token");
      assertNotEquals(user.getUsername(), account(mvc, token).get("username"));
    }
  }

  /** The default (prod today): the provider is off, so is linking; the account page says so. */
  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  class Legacy {

    @Autowired
    MockMvc mvc;
    @Autowired
    AuthService authService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
      database(registry);
    }

    @Test
    void noLinking() throws Exception {
      User user = authService.createAccount("legacy" + unique(), "password123", null);
      String jwt = authService.tokenFor(user.getUsername());
      assertEquals(false, account(mvc, jwt).get("oidc"));
      assertEquals(false, account(mvc, jwt).get("oidcLinked"));
      mvc.perform(post("/api/account/oidc-link").header("Authorization", "Bearer " + jwt))
          .andExpect(status().isNotFound());
      mvc.perform(delete("/api/account/oidc-link").header("Authorization", "Bearer " + jwt))
          .andExpect(status().isNotFound());
      mvc.perform(get("/api/oauth2/authorization/zitadel?link=x").header("Host", HOST))
          .andExpect(header().string("Location", not(startsWith(IDP.issuer()))));
    }
  }

  /** Only the provider: it is the only way in, so an account cannot be unlinked. */
  @Nested
  @SpringBootTest(properties = "app.auth.mode=oidc")
  @AutoConfigureMockMvc
  class OidcOnly {

    @Autowired
    MockMvc mvc;
    @Autowired
    AuthService authService;
    @Autowired
    UserRepository users;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
      database(registry);
    }

    @Test
    void noUnlink() throws Exception {
      String sub = "only-" + unique();
      User user = authService.createAccount("only" + unique(), null, null, sub);
      String jwt = authService.tokenFor(user.getUsername());
      Map<String, Object> account = account(mvc, jwt);
      assertEquals(true, account.get("oidcLinked"));
      assertEquals(false, account.get("oidcCanUnlink"));
      mvc.perform(delete("/api/account/oidc-link").header("Authorization", "Bearer " + jwt))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error").value("only-login"));
      assertEquals(sub, users.findById(user.getId()).orElseThrow().getIdpSubject());
    }
  }
}
