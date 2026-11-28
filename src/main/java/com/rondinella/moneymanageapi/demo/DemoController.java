package com.rondinella.moneymanageapi.demo;

import com.rondinella.moneymanageapi.auth.dto.AuthResponse;
import com.rondinella.moneymanageapi.auth.mail.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * "Prova la demo" (/demo in the web app): a sandbox of one's own with the sample data, without a password or a
 * sign-up. 404 while the demo is off (MONEY_DEMO_ENABLED); 429 with the code DEMO_LIMIT_IP or DEMO_FULL over the
 * limits; otherwise the token, as a login answers it.
 */
@RestController
public class DemoController {

  private final DemoSettings settings;
  private final DemoAccounts demos;

  public DemoController(DemoSettings settings, DemoAccounts demos) {
    this.settings = settings;
    this.demos = demos;
  }

  @PostMapping(path = "/api/demo", produces = "application/json")
  public ResponseEntity<AuthResponse> create(HttpServletRequest request) {
    if (!settings.enabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The demo is off");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(new AuthResponse(demos.create(ClientIp.of(request))));
  }

  @ExceptionHandler(DemoAccounts.Refused.class)
  ResponseEntity<Map<String, Object>> refused(DemoAccounts.Refused refused) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, "3600")
        .body(DemoGuard.body(HttpStatus.TOO_MANY_REQUESTS, refused.code(), Map.of()));
  }
}
