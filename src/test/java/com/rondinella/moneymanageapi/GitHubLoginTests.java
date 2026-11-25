package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.auth.github.GitHubUserService;
import com.rondinella.moneymanageapi.auth.github.GitHubUserService.GitHubEmail;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.github.client-id=test-client", "app.github.client-secret=test-secret"})
@AutoConfigureMockMvc
class GitHubLoginTests {

  @Autowired
  MockMvc mvc;
  @Autowired
  GitHubUserService gitHubUsers;
  @Autowired
  UserRepository users;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> EmbeddedDb.jdbcUrl(0));
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres");
  }

  @Test
  void theLoginStartsAtGitHubAndComesBackUnderApi() throws Exception {
    mvc.perform(get("/api/auth/providers"))
        .andExpect(jsonPath("$.github").value(true))
        .andExpect(jsonPath("$.githubUrl").value("/api/oauth2/authorization/github"));
    mvc.perform(get("/api/oauth2/authorization/github").header("Host", "money.example.org").header("X-Forwarded-Proto", "https"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", startsWith("https://github.com/login/oauth/authorize")))
        .andExpect(header().string("Location", containsString(
            "redirect_uri=https://money.example.org/api/login/oauth2/code/github")));
  }

  @Test
  void sameVerifiedEmailSameAccountUnverifiedNever() {
    String mail = "gh" + UUID.randomUUID().toString().substring(0, 8) + "@example.org";
    String first = gitHubUsers.toLocalUser(List.of(new GitHubEmail(mail, true, true)), "octo").getName();
    String again = gitHubUsers.toLocalUser(
        List.of(new GitHubEmail("other@example.org", true, false), new GitHubEmail(mail.toUpperCase(), false, true)), "octo").getName();
    assertEquals(first, again);
    assertEquals(mail, users.findByUsername(first).orElseThrow().getEmail());

    // the same email, but not verified: never linked, and no account without a verified email
    assertThrows(OAuth2AuthenticationException.class,
        () -> gitHubUsers.toLocalUser(List.of(new GitHubEmail(mail, true, false)), "octo"));
  }

  @Test
  void aPasswordAccountIsNotTakenOver() throws Exception {
    String name = "gh" + UUID.randomUUID().toString().substring(0, 6);
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"" + name + "\",\"password\":\"password123\"}"))
        .andExpect(status().isOk());
    String local = gitHubUsers.toLocalUser(List.of(new GitHubEmail(name + "@example.org", true, true)), name).getName();
    assertEquals(name + "-gh", local);
  }

  @Test
  void sampleDataOnRequestOnlyOnAnEmptyAccount() throws Exception {
    String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"s" + UUID.randomUUID().toString().substring(0, 8) + "\",\"password\":\"password123\"}"))
        .andReturn().getResponse().getContentAsString();
    String auth = "Bearer " + json.replaceAll(".*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    mvc.perform(post("/api/sample-data").header("Authorization", auth)).andExpect(status().isCreated());
    mvc.perform(get("/api/banks/transactions/accounts").header("Authorization", auth))
        .andExpect(jsonPath("$", hasSize(2)));
    mvc.perform(post("/api/sample-data").header("Authorization", auth)).andExpect(status().isConflict());
  }
}
