package com.rondinella.moneymanageapi.banking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * The "state" of the bank authorizations in progress (public.bank_auth_state): 32 random bytes, only their SHA-256
 * kept, valid 10 minutes and once. The callback has no login: the state is the only thing that says whose it is.
 */
@Component
public class BankAuthStates {

  public static final Duration TTL = Duration.ofMinutes(10);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** What the state stands for. */
  public record Pending(long userId, String aspspName, String aspspCountry, Instant validUntil) {
  }

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public BankAuthStates(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.clock = Clock.systemUTC();
  }

  public String create(long userId, String aspspName, String aspspCountry, Instant validUntil) {
    Instant now = clock.instant();
    jdbc.update("DELETE FROM public.bank_auth_state WHERE expires_at < ?", Timestamp.from(now.minus(Duration.ofDays(1))));
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    jdbc.update("""
        INSERT INTO public.bank_auth_state (state_hash, user_id, aspsp_name, aspsp_country, valid_until, created_at,
                                            expires_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)""",
        hash(state), userId, aspspName, aspspCountry, Timestamp.from(validUntil), Timestamp.from(now),
        Timestamp.from(now.plus(TTL)));
    return state;
  }

  /** Uses the state up: the first call within 10 minutes gets it, every other one nothing. */
  public Optional<Pending> consume(String state) {
    if (state == null || state.isBlank() || state.length() > 100) {
      return Optional.empty();
    }
    List<Pending> found = jdbc.query("""
            UPDATE public.bank_auth_state SET used_at = ?
             WHERE state_hash = ? AND used_at IS NULL AND expires_at > ?
            RETURNING user_id, aspsp_name, aspsp_country, valid_until""",
        (rs, i) -> new Pending(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()),
        Timestamp.from(clock.instant()), hash(state), Timestamp.from(clock.instant()));
    return found.stream().findFirst();
  }

  public static String hash(String state) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
