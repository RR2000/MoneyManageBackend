package com.rondinella.moneymanageapi.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

@Service
public class JwtService {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtService.class);

  @Value("${app.jwt.secret:}")
  private String secretKey;

  @Value("${app.jwt.expiration:86400000}")
  private long jwtExpiration;

  private SecretKey signingKey;

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
