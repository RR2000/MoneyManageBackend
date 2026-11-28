package com.rondinella.moneymanageapi.auth.oidc;

import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * "Collega a raoulrondinella.dev" and "Scollega" on the settings page, for the logged-in account (see
 * {@link OidcLinks}). Both answer 404 while the site's OpenID provider is off (AUTH_MODE=legacy or not configured).
 */
@RestController
public class OidcLinkController {

  private final OidcLinks links;
  private final OidcLogin oidc;
  private final UserRepository users;

  public OidcLinkController(OidcLinks links, OidcLogin oidc, UserRepository users) {
    this.links = links;
    this.oidc = oidc;
    this.users = users;
  }

  /**
   * A link request: where to send the browser ({"url": ...}, the provider's button with the request) and the cookie
   * that binds it to this browser. 409 {"error": "linked"} if the account already is.
   */
  @PostMapping(path = "/api/account/oidc-link", produces = "application/json")
  public ResponseEntity<Map<String, String>> start(@AuthenticationPrincipal UserDetails principal) {
    User user = current(principal);
    if (user.getIdpSubject() != null) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "linked"));
    }
    OidcLinks.Started started = links.start(user);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, started.cookie().toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(Map.of("url", started.url()));
  }

  /**
   * Unlinks the account: 204 (also if it was not linked); 409 {"error": "only-login"} if the provider is its only way
   * in (AUTH_MODE=oidc).
   */
  @DeleteMapping(path = "/api/account/oidc-link", produces = "application/json")
  public ResponseEntity<Map<String, String>> unlink(@AuthenticationPrincipal UserDetails principal) {
    User user = current(principal);
    if (!links.unlink(user)) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "only-login"));
    }
    return ResponseEntity.noContent().build();
  }

  private User current(UserDetails principal) {
    if (!oidc.enabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Single sign-on is off");
    }
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    return users.findByUsername(principal.getUsername())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }
}
