package com.rondinella.moneymanageapi.auth.oidc;

import com.rondinella.moneymanageapi.auth.JwtService;
import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Collega a raoulrondinella.dev": an account that is already logged in (password or GitHub) is linked to a person
 * of the site's OpenID provider, whatever the two emails are. The subject (sub) of that person is saved on this very
 * account; the email is never looked at.
 * <ol>
 *   <li>The web app asks for a link request ({@link #start}): a value signed with a key of its own (never a login
 *       token), valid {@link #TICKET_TTL} and once, bound to the browser that asked through a random value in a
 *       {@code __Host-} cookie (only its hash is in the request). It goes in the URL of the provider's button:
 *       /api/oauth2/authorization/zitadel?link=(request).</li>
 *   <li>There ({@link OidcLinkRequests}) the request is used up and the account is written into the authorization
 *       request that Spring Security keeps in the session with the state: the state now stands for this account,
 *       for {@link #STATE_TTL} and once.</li>
 *   <li>Back from the provider ({@link #complete}): the subject is saved on that account; refused if another account
 *       has it, or if this one is already linked to another subject.</li>
 * </ol>
 * A browser that did not ask cannot be made to link: a request sent to someone else (in a link, a page) lacks the
 * cookie of their browser.
 */
@Component
public class OidcLinks {

  public static final String PURPOSE = "money-oidc-link";
  /** In the name: only this site over HTTPS can set it, never a sibling subdomain. */
  public static final String COOKIE = "__Host-money-oidc-link";
  public static final String PARAMETER = "link";
  public static final Duration TICKET_TTL = Duration.ofMinutes(2);
  /** Time to log in (or sign up) at the provider; the session that keeps the state lasts as much. */
  public static final Duration STATE_TTL = Duration.ofMinutes(10);
  /** Attributes of the authorization request (kept in the session, never sent to the provider). */
  static final String ATTR_USER = "money.link.user";
  static final String ATTR_UNTIL = "money.link.until";

  /** Error codes of a refused link, shown by the web app on the settings page (/settings#oidc-link-error=code). */
  public static final String EXPIRED = "expired";
  public static final String ALREADY_LINKED = "already-linked";
  public static final String OTHER_SUBJECT = "other-subject";
  public static final String FAILED = "failed";

  private static final SecureRandom RANDOM = new SecureRandom();

  /** What the web app gets: where to send the browser, and the cookie that binds the request to it. */
  public record Started(String url, ResponseCookie cookie) {
  }

  /** A refused link: the code goes to the settings page, never to the login page. */
  public static final class Refused extends OAuth2AuthenticationException {
    public Refused(String code) {
      super(new OAuth2Error(code), code);
    }

    public String code() {
      return getError().getErrorCode();
    }
  }

  private final JwtService jwt;
  private final UserRepository users;
  private final OidcLogin oidc;
  private final Clock clock;
  /** Link requests already used, until they expire (one backend; the authorization request is in its session too). */
  private final Map<String, Instant> used = new ConcurrentHashMap<>();

  @Autowired
  public OidcLinks(JwtService jwt, UserRepository users, OidcLogin oidc) {
    this(jwt, users, oidc, Clock.systemUTC());
  }

  OidcLinks(JwtService jwt, UserRepository users, OidcLogin oidc, Clock clock) {
    this.jwt = jwt;
    this.users = users;
    this.oidc = oidc;
    this.clock = clock;
  }

  /** A link request for this account (logged in), not yet linked; null subject only. */
  public Started start(User user) {
    String browser = random();
    String ticket = jwt.signForPurpose(PURPOSE, String.valueOf(user.getId()), random(),
        Map.of("bnd", sha256(browser)), TICKET_TTL);
    ResponseCookie cookie = ResponseCookie.from(COOKIE, browser)
        .path("/")
        .secure(true)
        .httpOnly(true)
        .sameSite("Lax")
        .maxAge(TICKET_TTL)
        .build();
    return new Started(OidcLogin.START_URL + "?" + PARAMETER + "=" + ticket, cookie);
  }

  /**
   * The authorization request, bound to the account of the link request in this request's URL (used up here), or
   * unchanged without one. A link request that is not good (signature, expiry, used, another browser) is refused.
   */
  OAuth2AuthorizationRequest bind(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
    String ticket = request.getParameter(PARAMETER);
    if (ticket == null) {
      return authorization;
    }
    long userId = consume(ticket, cookie(request)).orElseThrow(() -> new Refused(EXPIRED));
    Instant until = clock.instant().plus(STATE_TTL);
    return OAuth2AuthorizationRequest.from(authorization)
        .attributes(a -> {
          a.put(ATTR_USER, userId);
          a.put(ATTR_UNTIL, until.toEpochMilli());
        })
        .build();
  }

  /** The account a link request stands for, the first time only (and only from the browser it was given to). */
  Optional<Long> consume(String ticket, String browser) {
    Optional<Claims> claims = jwt.verifyForPurpose(PURPOSE, ticket);
    if (claims.isEmpty() || browser == null || browser.isBlank()
        || !MessageDigest.isEqual(sha256(browser).getBytes(StandardCharsets.US_ASCII),
        String.valueOf(claims.get().get("bnd")).getBytes(StandardCharsets.US_ASCII))) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    used.values().removeIf(expiry -> expiry.isBefore(now));
    String id = claims.get().getId();
    if (id == null || used.putIfAbsent(id, claims.get().getExpiration().toInstant()) != null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(claims.get().getSubject()));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /** The account the state was bound to by {@link #bind}, if this login is a link. */
  static Optional<Long> linkUser(OAuth2AuthorizationRequest authorization) {
    Object user = authorization.getAttribute(ATTR_USER);
    return user instanceof Number n ? Optional.of(n.longValue()) : Optional.empty();
  }

  /** Back from the provider with a link: saves the subject on the account the state stands for. */
  User complete(OAuth2AuthorizationRequest authorization, String subject) {
    long userId = linkUser(authorization).orElseThrow(() -> new Refused(FAILED));
    Object until = authorization.getAttribute(ATTR_UNTIL);
    if (!(until instanceof Number n) || Instant.ofEpochMilli(n.longValue()).isBefore(clock.instant())) {
      throw new Refused(EXPIRED);
    }
    if (subject == null || subject.isBlank()) {
      throw new Refused(FAILED);
    }
    User user = users.findById(userId).orElseThrow(() -> new Refused(FAILED));
    if (subject.equals(user.getIdpSubject())) {
      return user;
    }
    if (user.getIdpSubject() != null) {
      throw new Refused(OTHER_SUBJECT);
    }
    Optional<User> other = users.findByIdpSubject(subject);
    if (other.isPresent() && !Objects.equals(other.get().getId(), user.getId())) {
      throw new Refused(ALREADY_LINKED);
    }
    user.setIdpSubject(subject);
    try {
      return users.saveAndFlush(user);
    } catch (DataIntegrityViolationException e) {
      // linked to another account in the meantime (unique column)
      throw new Refused(ALREADY_LINKED);
    }
  }

  /**
   * Whether the account could still get in without the provider: only while the password login is on (AUTH_MODE
   * legacy or both: password, "forgot password" by email, GitHub). With AUTH_MODE=oidc the provider is the only way,
   * so it cannot be unlinked.
   */
  public boolean canUnlink(User user) {
    return user.getIdpSubject() != null && oidc.passwordLogin();
  }

  /** Unlinks the account; false if it would lock its owner out ({@link #canUnlink}). Unlinked already: true. */
  public boolean unlink(User user) {
    if (user.getIdpSubject() == null) {
      return true;
    }
    if (!canUnlink(user)) {
      return false;
    }
    user.setIdpSubject(null);
    users.save(user);
    return true;
  }

  private static String cookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie c : cookies) {
      if (COOKIE.equals(c.getName())) {
        return c.getValue();
      }
    }
    return null;
  }

  private static String random() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
