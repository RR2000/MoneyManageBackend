package com.rondinella.moneymanageapi.auth.mail;

import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.auth.oidc.OidcLinks;
import com.rondinella.moneymanageapi.auth.oidc.OidcLogin;
import com.rondinella.moneymanageapi.banking.BankingSettings;
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
  private final BankingSettings banking;
  private final OidcLogin oidc;
  private final OidcLinks oidcLinks;

  public EmailLinksController(EmailLinks links, UserRepository users, BankingSettings banking, OidcLogin oidc,
                              OidcLinks oidcLinks) {
    this.links = links;
    this.users = users;
    this.banking = banking;
    this.oidc = oidc;
    this.oidcLinks = oidcLinks;
  }

  /** Always 202, whether an account has this email or not. */
  @PostMapping("/api/auth/password/forgot")
  public ResponseEntity<Void> forgot(@RequestBody EmailRequest request, HttpServletRequest http) {
    requirePasswordLogin();
    links.requestReset(request.email(), ClientIp.of(http));
    return ResponseEntity.accepted().build();
  }

  /** The new password; the answer says the username to log in with (or the account's email). */
  @PostMapping(path = "/api/auth/password/reset", produces = "application/json")
  public Map<String, String> reset(@RequestBody ResetRequest request) {
    requirePasswordLogin();
    return Map.of("username", links.resetPassword(request.token(), request.password()));
  }

  /** The link of the confirmation email: it works without being logged in (the token is the proof). */
  @PostMapping("/api/auth/email/confirm")
  public ResponseEntity<Void> confirm(@RequestBody TokenRequest request) {
    links.confirmEmail(request.token());
    return ResponseEntity.noContent().build();
  }

  /**
   * The logged-in account: username, confirmed email (null if none), which optional features it has. oidc: the site's
   * single sign-on is on (the settings page offers "Collega a raoulrondinella.dev"); oidcLinked: this account is
   * linked to it; oidcCanUnlink: it can be unlinked (not with AUTH_MODE=oidc, where it is the only way in).
   */
  @GetMapping(path = "/api/account", produces = "application/json")
  public Map<String, Object> account(@AuthenticationPrincipal UserDetails principal) {
    User user = current(principal);
    Map<String, Object> body = new HashMap<>();
    body.put("username", user.getUsername());
    body.put("email", user.getEmail());
    body.put("emailLinks", links.enabled());
    // open banking: only for the enabled users (the web app shows the banks page only then)
    body.put("banking", banking.enabledFor(user.getId()));
    body.put("oidc", oidc.enabled());
    body.put("oidcLinked", oidc.enabled() && user.getIdpSubject() != null);
    body.put("oidcCanUnlink", oidc.enabled() && oidcLinks.canUnlink(user));
    return body;
  }

  /** Sends a confirmation link to the new email; the account changes only when it is opened. */
  @PostMapping("/api/account/email")
  public ResponseEntity<Void> changeEmail(@AuthenticationPrincipal UserDetails principal,
                                          @RequestBody EmailRequest request, HttpServletRequest http) {
    links.requestEmailChange(current(principal), request.email(), ClientIp.of(http));
    return ResponseEntity.accepted().build();
  }

  /** AUTH_MODE=oidc: passwords are not used, so there is nothing to reset. */
  private void requirePasswordLogin() {
    if (!oidc.passwordLogin()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Password login is off");
    }
  }

  private User current(UserDetails principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return users.findByUsername(principal.getUsername())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }
}
