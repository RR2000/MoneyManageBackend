package com.rondinella.moneymanageapi.auth;

import com.rondinella.moneymanageapi.auth.github.GitHubLogin;
import com.rondinella.moneymanageapi.auth.mail.ClientIp;
import com.rondinella.moneymanageapi.auth.mail.EmailLinks;
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

  public AuthController(AuthService authService, GitHubLogin gitHub, EmailLinks emailLinks) {
    this.authService = authService;
    this.gitHub = gitHub;
    this.emailLinks = emailLinks;
  }

  /** Which ways to log in the web app should offer, and where they start; passwordReset: "forgot password" by email. */
  @GetMapping("/providers")
  public Map<String, Object> providers() {
    Map<String, Object> body = new HashMap<>();
    body.put("github", gitHub.enabled());
    if (gitHub.enabled()) {
      body.put("githubUrl", GitHubLogin.AUTHORIZATION_BASE + "/github");
    }
    body.put("passwordReset", emailLinks.enabled());
    return body;
  }

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request, HttpServletRequest http) {
    return ResponseEntity.ok(authService.register(request, ClientIp.of(http)));
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
    return ResponseEntity.ok(authService.login(request));
  }
}
