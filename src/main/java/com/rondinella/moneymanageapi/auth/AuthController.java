package com.rondinella.moneymanageapi.auth;

import com.rondinella.moneymanageapi.auth.github.GitHubLogin;
import com.rondinella.moneymanageapi.auth.mail.ClientIp;
import com.rondinella.moneymanageapi.auth.mail.EmailLinks;
import com.rondinella.moneymanageapi.auth.oidc.OidcLogin;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

import com.rondinella.moneymanageapi.auth.dto.AuthResponse;
import com.rondinella.moneymanageapi.auth.dto.LoginRequest;
import com.rondinella.moneymanageapi.auth.dto.RegisterRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/auth", produces = "application/json")
public class AuthController {

  private final AuthService authService;
  private final GitHubLogin gitHub;
  private final EmailLinks emailLinks;
  private final OidcLogin oidc;

  public AuthController(AuthService authService, GitHubLogin gitHub, EmailLinks emailLinks, OidcLogin oidc) {
    this.authService = authService;
    this.gitHub = gitHub;
    this.emailLinks = emailLinks;
    this.oidc = oidc;
  }

  /**
   * Which ways to log in the web app should offer, and where they start: password (login and sign-up), GitHub,
   * passwordReset ("forgot password" by email), oidc ("Entra con raoulrondinella.dev", with oidcLogoutUrl once the
   * provider has been reached: the web app adds id_token_hint).
   */
  @GetMapping("/providers")
  public Map<String, Object> providers() {
    Map<String, Object> body = new HashMap<>();
    boolean password = oidc.passwordLogin();
    body.put("password", password);
    body.put("github", password && gitHub.enabled());
    if (password && gitHub.enabled()) {
      body.put("githubUrl", GitHubLogin.AUTHORIZATION_BASE + "/github");
    }
    body.put("passwordReset", password && emailLinks.enabled());
    body.put("oidc", oidc.enabled());
    if (oidc.enabled()) {
      body.put("oidcUrl", OidcLogin.START_URL);
      oidc.logoutUrl().ifPresent(url -> body.put("oidcLogoutUrl", url));
    }
    return body;
  }

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request, HttpServletRequest http) {
    requirePasswordLogin();
    return ResponseEntity.ok(authService.register(request, ClientIp.of(http)));
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
    requirePasswordLogin();
    return ResponseEntity.ok(authService.login(request));
  }

  /** AUTH_MODE=oidc: only the site's single sign-on. */
  private void requirePasswordLogin() {
    if (!oidc.passwordLogin()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Password login is off");
    }
  }
}
