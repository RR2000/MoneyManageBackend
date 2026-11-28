package com.rondinella.moneymanageapi.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Service
public class JwtService {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtService.class);

  @Value("${app.jwt.secret:}")
  private String secretKey;

  @Value("${app.jwt.expiration:86400000}")
  private long jwtExpiration;

  private SecretKey signingKey;
  /** For other signed values (the request to link the single sign-on): a key of its own, never a login token. */
  private SecretKey purposeKey;

  @PostConstruct
  void init() {
    if (secretKey != null && !secretKey.isBlank()) {
      byte[] bytes = secretKey.getBytes(StandardCharsets.UTF_8);
      // a short or made-up secret ("change-me") can be guessed, and with it any token forged: refuse to start
      if (bytes.length < 32 || secretKey.chars().distinct().count() < 10) {
        throw new IllegalStateException("app.jwt.secret (MONEY_JWT_SECRET) must be at least 32 random characters");
      }
      this.signingKey = Keys.hmacShaKeyFor(bytes);
    } else {
      // No secret configured: random key for this run (tokens do not survive a restart).
      log.info("No MONEY_JWT_SECRET: random signing key, logins end at every restart");
      this.signingKey = Jwts.SIG.HS256.key().build();
    }
    this.purposeKey = Keys.hmacShaKeyFor(hmac(signingKey.getEncoded(), "money purpose-signed values"));
  }

  /**
   * A short-lived signed value for one purpose (the audience), signed with a key derived from the login key: it is
   * never accepted as a login token, and a login token is never accepted here.
   */
  public String signForPurpose(String purpose, String subject, String id, Map<String, String> claims, Duration ttl) {
    long now = System.currentTimeMillis();
    var builder = Jwts.builder()
        .audience().add(purpose).and()
        .subject(subject)
        .id(id)
        .issuedAt(new Date(now))
        .expiration(new Date(now + ttl.toMillis()));
    claims.forEach(builder::claim);
    return builder.signWith(purposeKey, Jwts.SIG.HS256).compact();
  }

  /** The claims of a value signed by {@link #signForPurpose} for this purpose and not expired, or empty. */
  public Optional<Claims> verifyForPurpose(String purpose, String value) {
    if (value == null || value.isBlank() || value.length() > 2000) {
      return Optional.empty();
    }
    try {
      return Optional.of(Jwts.parser()
          .verifyWith(purposeKey)
          .requireAudience(purpose)
          .build()
          .parseSignedClaims(value)
          .getPayload());
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static byte[] hmac(byte[] key, String label) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public String extractUsername(String token) {
    return extractClaim(token, Claims::getSubject);
  }

  public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    return claimsResolver.apply(extractAllClaims(token));
  }

  public String generateToken(String username) {
    long now = System.currentTimeMillis();
    return Jwts.builder()
        .subject(username)
        .issuedAt(new Date(now))
        .expiration(new Date(now + jwtExpiration))
        .signWith(signingKey, Jwts.SIG.HS256)
        .compact();
  }

  public boolean isTokenValid(String token, UserDetails userDetails) {
    return extractUsername(token).equals(userDetails.getUsername()) && !isTokenExpired(token);
  }

  private boolean isTokenExpired(String token) {
    return extractClaim(token, Claims::getExpiration).before(new Date());
  }

  private Claims extractAllClaims(String token) {
    return Jwts.parser()
        .verifyWith(signingKey)
        .build()
        .parseSignedClaims(token)
        .getPayload();
  }
}
