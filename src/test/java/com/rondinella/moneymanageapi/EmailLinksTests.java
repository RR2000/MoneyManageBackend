package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.auth.mail.EmailLinks;
import com.rondinella.moneymanageapi.auth.mail.Mailer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** "Forgot password" and the account's email, with the mail sender mocked: no mail leaves the tests. */
@SpringBootTest(properties = {
    "app.mail.host=smtp.example.org", "app.mail.username=user", "app.mail.password=secret",
    "app.mail.from=noreply@example.org", "app.web-url=https://money.example.org/",
    "app.auth.max-registrations=10000"})
@AutoConfigureMockMvc
class EmailLinksTests {

  private static final Pattern RESET_LINK = Pattern.compile("https://money\\.example\\.org/reset-password#token=([A-Za-z0-9_-]{43})");
  private static final Pattern CONFIRM_LINK = Pattern.compile("https://money\\.example\\.org/verify-email#token=([A-Za-z0-9_-]{43})");
  private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

  @Autowired
  MockMvc mvc;
  @Autowired
  UserRepository users;
  @Autowired
  JdbcTemplate jdbc;
  @MockitoBean
  Mailer mailer;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  private static String name() {
    return "m" + UUID.randomUUID().toString().substring(0, 8);
  }

  /** A different client address for every test: the per-address limit does not leak between tests. */
  private static String ip() {
    return "10.1." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250);
  }

  private String register(String username, String email) throws Exception {
    String body = "{\"username\":\"" + username + "\",\"password\":\"password123\""
        + (email == null ? "" : ",\"email\":\"" + email + "\"") + "}";
    String json = mvc.perform(post("/api/auth/register").header("X-Real-IP", ip())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    Matcher m = TOKEN.matcher(json);
    assertTrue(m.find());
    return "Bearer " + m.group(1);
  }

  /** An account with a confirmed email, put straight in the database. */
  private String accountWithEmail(String username, String email) throws Exception {
    register(username, null);
    User user = users.findByUsername(username).orElseThrow();
    user.setEmail(email);
    users.save(user);
    return username;
  }

  private ResultActions forgot(String email, String ip) throws Exception {
    return mvc.perform(post("/api/auth/password/forgot").header("X-Real-IP", ip)
        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"));
  }

  private String mailedToken(String to, Pattern link) {
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(mailer, timeout(5000)).send(eq(to), anyString(), text.capture());
    Matcher m = link.matcher(text.getValue());
    assertTrue(m.find(), "no link in the mail");
    return m.group(1);
  }

  private ResultActions reset(String token, String password) throws Exception {
    return mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"));
  }

  private ResultActions login(String username, String password) throws Exception {
    return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
  }

  @Test
  void theWebAppIsToldTheFeatureIsOn() throws Exception {
    mvc.perform(get("/api/auth/providers")).andExpect(jsonPath("$.passwordReset").value(true));
  }

  @Test
  void sameAnswerWhetherTheAccountExistsOrNot() throws Exception {
    String email = name() + "@example.com";
    String username = accountWithEmail(name(), email);
    String nobody = name() + "@example.com";

    MvcResult known = forgot(email, ip()).andExpect(status().isAccepted()).andReturn();
    MvcResult unknown = forgot(nobody, ip()).andExpect(status().isAccepted()).andReturn();
    assertEquals(known.getResponse().getContentAsString(), unknown.getResponse().getContentAsString());
    assertEquals(known.getResponse().getHeaderNames(), unknown.getResponse().getHeaderNames());

    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(mailer, timeout(5000)).send(eq(email), ArgumentMatchers.contains("reimposta la password"), text.capture());
    assertThat(text.getValue(), containsString("\"" + username + "\""));
    assertThat(text.getValue(), containsString("30 minuti"));
    verify(mailer, after(300).never()).send(eq(nobody), anyString(), anyString());
  }

  @Test
  void theLinkSetsANewPasswordOnceAndIsStoredOnlyAsAHash() throws Exception {
    String email = name() + "@example.com";
    String username = accountWithEmail(name(), email);
    forgot(email.toUpperCase(), ip()).andExpect(status().isAccepted());
    String token = mailedToken(email, RESET_LINK);

    List<String> stored = jdbc.queryForList("SELECT token_hash FROM email_tokens", String.class);
    assertTrue(stored.contains(EmailLinks.hash(token)));
    assertFalse(stored.contains(token));

    reset(token, "short").andExpect(status().isBadRequest());
    reset(token, "new-password-1").andExpect(status().isNoContent());
    login(username, "new-password-1").andExpect(status().isOk());
    login(username, "password123").andExpect(status().isUnauthorized());

    // single use
    reset(token, "new-password-2").andExpect(status().isBadRequest())
        .andExpect(status().reason("This link is not valid or has expired"));
    reset("not-a-token", "new-password-2").andExpect(status().isBadRequest());
    login(username, "new-password-1").andExpect(status().isOk());
  }

  @Test
  void anExpiredLinkIsRefused() throws Exception {
    String email = name() + "@example.com";
    accountWithEmail(name(), email);
    forgot(email, ip()).andExpect(status().isAccepted());
    String token = mailedToken(email, RESET_LINK);
    jdbc.update("UPDATE email_tokens SET expires_at = now() - interval '1 minute' WHERE token_hash = ?",
        EmailLinks.hash(token));
    reset(token, "new-password-1").andExpect(status().isBadRequest());
  }

  @Test
  void aNewPasswordCancelsTheOtherLinks() throws Exception {
    String email = name() + "@example.com";
    accountWithEmail(name(), email);
    String ip = ip();
    forgot(email, ip).andExpect(status().isAccepted());
    forgot(email, ip).andExpect(status().isAccepted());
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(mailer, timeout(5000).times(2)).send(eq(email), anyString(), text.capture());
    List<String> links = text.getAllValues().stream().map(t -> {
      Matcher m = RESET_LINK.matcher(t);
      assertTrue(m.find());
      return m.group(1);
    }).toList();
    assertNotEquals(links.get(0), links.get(1));
    reset(links.get(1), "new-password-1").andExpect(status().isNoContent());
    reset(links.get(0), "new-password-2").andExpect(status().isBadRequest());
  }

  @Test
  void atMostThreeMailsPerEmailWithTheSameAnswer() throws Exception {
    String email = name() + "@example.com";
    accountWithEmail(name(), email);
    for (int i = 0; i < 5; i++) {
      forgot(email, ip()).andExpect(status().isAccepted());
    }
    verify(mailer, after(500).times(3)).send(eq(email), anyString(), anyString());
  }

  @Test
  void tooManyRequestsFromOneAddress() throws Exception {
    String ip = ip();
    for (int i = 0; i < 10; i++) {
      forgot(name() + "@example.com", ip).andExpect(status().isAccepted());
    }
    forgot(name() + "@example.com", ip).andExpect(status().isTooManyRequests());
    forgot(name() + "@example.com", ip()).andExpect(status().isAccepted());
  }

  @Test
  void anEmailBelongsToTheAccountOnlyOnceConfirmed() throws Exception {
    String username = name();
    String auth = register(username, null);
    String email = name() + "@example.com";

    mvc.perform(get("/api/account").header("Authorization", auth))
        .andExpect(jsonPath("$.username").value(username))
        .andExpect(jsonPath("$.email").value(nullValue()))
        .andExpect(jsonPath("$.emailLinks").value(true));
    mvc.perform(post("/api/account/email").header("Authorization", auth).header("X-Real-IP", ip())
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
        .andExpect(status().isAccepted());
    String token = mailedToken(email, CONFIRM_LINK);

    // not yet confirmed: a reset sends nothing
    forgot(email, ip()).andExpect(status().isAccepted());
    verify(mailer, after(300).times(1)).send(eq(email), anyString(), anyString());

    mvc.perform(post("/api/auth/email/confirm").contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\"}")).andExpect(status().isNoContent());
    mvc.perform(post("/api/auth/email/confirm").contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\"}")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/account").header("Authorization", auth)).andExpect(jsonPath("$.email").value(email));

    forgot(email, ip()).andExpect(status().isAccepted());
    verify(mailer, timeout(5000).times(2)).send(eq(email), anyString(), anyString());
  }

  @Test
  void anEmailGivenAtRegistrationGetsAConfirmationLink() throws Exception {
    String email = name() + "@example.com";
    String auth = register(name(), email);
    String token = mailedToken(email, CONFIRM_LINK);
    mvc.perform(post("/api/auth/email/confirm").contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\"}")).andExpect(status().isNoContent());
    mvc.perform(get("/api/account").header("Authorization", auth)).andExpect(jsonPath("$.email").value(email));

    mvc.perform(post("/api/auth/register").header("X-Real-IP", ip()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name() + "\",\"password\":\"password123\",\"email\":\"not-an-email\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anEmailTakenMeanwhileIsNotStolen() throws Exception {
    String email = name() + "@example.com";
    String auth = register(name(), null);
    mvc.perform(post("/api/account/email").header("Authorization", auth).header("X-Real-IP", ip())
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
        .andExpect(status().isAccepted());
    String token = mailedToken(email, CONFIRM_LINK);
    accountWithEmail(name(), email);
    mvc.perform(post("/api/auth/email/confirm").contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\"}")).andExpect(status().isConflict());
  }

  @Test
  void theAccountNeedsALogin() throws Exception {
    mvc.perform(get("/api/account")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/account/email").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@example.com\"}"))
        .andExpect(status().isUnauthorized());
  }
}
