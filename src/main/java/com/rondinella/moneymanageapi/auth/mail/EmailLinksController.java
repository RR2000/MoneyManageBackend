package com.rondinella.moneymanageapi.auth.mail;

import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * Password reset by email and the account's email. The public endpoints (under /api/auth) take tokens and emails in
 * the body, never in the URL (it would end up in the access logs).
 */
@RestController
public class EmailLinksController {

  public record EmailRequest(String email) {
  }

  public record ResetRequest(String token, String password) {
  }

  public record TokenRequest(String token) {
  }

  private final EmailLinks links;
  private final UserRepository users;

  public EmailLinksController(EmailLinks links, UserRepository users) {
    this.links = links;
    this.users = users;
  }

  /** Always 202, whether an account has this email or not. */
  @PostMapping("/api/auth/password/forgot")
  public ResponseEntity<Void> forgot(@RequestBody EmailRequest request, HttpServletRequest http) {
    links.requestReset(request.email(), ClientIp.of(http));
    return ResponseEntity.accepted().build();
  }

  @PostMapping("/api/auth/password/reset")
  public ResponseEntity<Void> reset(@RequestBody ResetRequest request) {
    links.resetPassword(request.token(), request.password());
    return ResponseEntity.noContent().build();
  }

  /** The link of the confirmation email: it works without being logged in (the token is the proof). */
  @PostMapping("/api/auth/email/confirm")
  public ResponseEntity<Void> confirm(@RequestBody TokenRequest request) {
    links.confirmEmail(request.token());
    return ResponseEntity.noContent().build();
  }

  /** The logged-in account: username and confirmed email (null if none). */
  @GetMapping(path = "/api/account", produces = "application/json")
  public Map<String, Object> account(@AuthenticationPrincipal UserDetails principal) {
    User user = current(principal);
    Map<String, Object> body = new HashMap<>();
    body.put("username", user.getUsername());
    body.put("email", user.getEmail());
    body.put("emailLinks", links.enabled());
    return body;
  }

  /** Sends a confirmation link to the new email; the account changes only when it is opened. */
  @PostMapping("/api/account/email")
  public ResponseEntity<Void> changeEmail(@AuthenticationPrincipal UserDetails principal,
                                          @RequestBody EmailRequest request, HttpServletRequest http) {
    links.requestEmailChange(current(principal), request.email(), ClientIp.of(http));
    return ResponseEntity.accepted().build();
  }

  private User current(UserDetails principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return users.findByUsername(principal.getUsername())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }
}
