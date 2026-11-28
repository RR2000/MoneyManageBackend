package com.rondinella.moneymanageapi.auth.oidc;

import com.rondinella.moneymanageapi.auth.github.GitHubLogin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * "Entra con raoulrondinella.dev": login through the single sign-on of the site (an OpenID Connect provider, Zitadel),
 * next to or instead of the password and GitHub logins (AUTH_MODE, see {@link AuthMode}). On only when the mode is
 * both or oidc and MONEY_OIDC_ISSUER and MONEY_OIDC_CLIENT_ID are set.
 * <p>
 * The client is public: authorization code with PKCE (S256), no secret. The code is exchanged by this backend, at the
 * exact redirect URI registered at the provider (https://(site)/api/login/oauth2/code/zitadel), with the verifier
 * that never left the server; a secret would add little to that and would be one more thing to keep and rotate.
 * <p>
 * The provider's configuration (endpoints, keys) is read from its discovery document right after startup and kept;
 * while the provider cannot be reached its button leads back to the login page with an error, and the other logins
 * work as before. The site never waits for the provider to start.
 */
@Component
public class OidcLogin {

  public static final String REGISTRATION_ID = "zitadel";
  /** Where the web app's button points to (Spring Security's authorization endpoint, under /api). */
  public static final String START_URL = GitHubLogin.AUTHORIZATION_BASE + "/" + REGISTRATION_ID;

  private static final Logger log = LoggerFactory.getLogger(OidcLogin.class);
  private static final Duration RETRY = Duration.ofMinutes(1);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AuthMode mode;
  private final String issuer;
  private final String clientId;
  private final String webUrl;
  private final Clock clock = Clock.systemUTC();
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();

  private volatile ClientRegistration registration;
  private volatile Instant lastFailure;

  public OidcLogin(@Value("${app.auth.mode:legacy}") String mode,
                   @Value("${app.oidc.issuer:}") String issuer,
                   @Value("${app.oidc.client-id:}") String clientId,
                   @Value("${app.web-url:}") String webUrl) {
    this.mode = AuthMode.parse(mode);
    this.issuer = trimSlash(issuer);
    this.clientId = clientId == null ? "" : clientId.trim();
    this.webUrl = trimSlash(webUrl);
    boolean configured = !this.issuer.isEmpty() && !this.clientId.isEmpty();
    if (this.mode == AuthMode.OIDC && !configured) {
      // nobody could log in: better not to start than to start locked
      throw new IllegalStateException("AUTH_MODE=oidc needs MONEY_OIDC_ISSUER and MONEY_OIDC_CLIENT_ID");
    }
    if (this.mode == AuthMode.BOTH && !configured) {
      log.warn("AUTH_MODE=both without MONEY_OIDC_ISSUER or MONEY_OIDC_CLIENT_ID: only the usual logins");
    }
  }

  public AuthMode mode() {
    return mode;
  }

  /** The button is shown and its endpoints work. */
  public boolean enabled() {
    return mode != AuthMode.LEGACY && !issuer.isEmpty() && !clientId.isEmpty();
  }

  /** Username and password (login, sign-up, reset by email) and "Accedi con GitHub": off with AUTH_MODE=oidc. */
  public boolean passwordLogin() {
    return mode != AuthMode.OIDC;
  }

  /** The provider's registration, or null while it cannot be reached (asked again at most once a minute). */
  public ClientRegistration registration() {
    if (!enabled()) {
      return null;
    }
    ClientRegistration known = registration;
    if (known != null) {
      return known;
    }
    synchronized (this) {
      if (registration != null) {
        return registration;
      }
      Instant failed = lastFailure;
      if (failed != null && failed.plus(RETRY).isAfter(clock.instant())) {
        return null;
      }
      try {
        registration = ClientRegistrations.fromOidcConfiguration(discovery())
            .registrationId(REGISTRATION_ID)
            .clientId(clientId)
            .clientSecret(null)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}" + GitHubLogin.CALLBACK_BASE + "/{registrationId}")
            .scope("openid", "profile", "email")
            .userNameAttributeName(IdTokenClaimNames.SUB)
            .clientName("raoulrondinella.dev")
            .build();
        lastFailure = null;
        log.info("OpenID provider configuration read: login with it on");
        return registration;
      } catch (RuntimeException e) {
        lastFailure = clock.instant();
        // only the kind of error: messages can carry URLs and bodies
        log.warn("OpenID provider not reachable ({}): its login is off for a minute", e.getClass().getSimpleName());
        return null;
      }
    }
  }

  /**
   * The provider's logout (end_session_endpoint) with this client and the way back to the site; the web app adds
   * id_token_hint. Empty while the provider has not been reached yet (never waits for it).
   */
  public Optional<String> logoutUrl() {
    ClientRegistration known = registration;
    if (known == null) {
      return Optional.empty();
    }
    Object endSession = known.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
    if (!(endSession instanceof String url) || url.isBlank()) {
      return Optional.empty();
    }
    StringBuilder out = new StringBuilder(url).append(url.contains("?") ? '&' : '?')
        .append("client_id=").append(encode(clientId));
    if (!webUrl.isEmpty()) {
      // registered at the provider as post-logout redirect URI, exactly
      out.append("&post_logout_redirect_uri=").append(encode(webUrl + "/"));
    }
    return Optional.of(out.toString());
  }

  /** Reads the provider's configuration once the site is up, so the first click does not wait for it. */
  @EventListener(ApplicationReadyEvent.class)
  void warmUp() {
    if (enabled()) {
      Thread.ofVirtual().name("oidc-discovery").start(this::registration);
    }
  }

  /** The discovery document, only if it names the configured issuer (as OpenID Connect Discovery requires). */
  private Map<String, Object> discovery() {
    HttpRequest request = HttpRequest.newBuilder(URI.create(issuer + "/.well-known/openid-configuration"))
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .GET()
        .build();
    HttpResponse<String> response;
    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("discovery not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException("discovery answered " + response.statusCode());
    }
    Map<String, Object> config = JSON.readValue(response.body(), new TypeReference<Map<String, Object>>() {
    });
    if (!issuer.equals(trimSlash(String.valueOf(config.get("issuer"))))) {
      throw new IllegalStateException("discovery names another issuer");
    }
    return config;
  }

  private static String trimSlash(String value) {
    String v = value == null ? "" : value.trim();
    while (v.endsWith("/")) {
      v = v.substring(0, v.length() - 1);
    }
    return v;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
