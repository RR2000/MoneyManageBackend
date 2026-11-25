package com.rondinella.moneymanageapi.auth;

import com.rondinella.moneymanageapi.auth.github.GitHubLogin;
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

  public AuthController(AuthService authService, GitHubLogin gitHub) {
    this.authService = authService;
    this.gitHub = gitHub;
  }

  /** Which ways to log in the web app should offer, and where they start. */
  @GetMapping("/providers")
  public Map<String, Object> providers() {
    return gitHub.enabled()
        ? Map.of("github", true, "githubUrl", GitHubLogin.AUTHORIZATION_BASE + "/github")
        : Map.of("github", false);
  }

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
    return ResponseEntity.ok(authService.register(request));
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
    return ResponseEntity.ok(authService.login(request));
  }
}
