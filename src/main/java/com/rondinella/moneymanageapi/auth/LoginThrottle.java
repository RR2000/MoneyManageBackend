package com.rondinella.moneymanageapi.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Brakes on guessing, in the backend (the reverse proxy also limits requests per address):
 * <ul>
 *   <li>login: after {@code maxFailures} wrong passwords for one username in {@code window}, that username is
 *   refused (429) until the window passes. Counted for every username asked, existing or not, so the answer
 *   never tells which accounts exist;</li>
 *   <li>registration: at most {@code maxRegistrations} new accounts in {@code window} for the whole site
 *   (every account is a new database schema).</li>
 * </ul>
 * In memory: a restart forgets it, enough for one instance.
 */
@Component
public class LoginThrottle {

  private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);
  /** more distinct usernames than this in one window: the oldest are forgotten (memory stays bounded) */
  private static final int MAX_TRACKED = 10_000;

  private final int maxFailures;
  private final int maxRegistrations;
  private final Duration window;
  private final Clock clock;

  private final Map<String, Deque<Instant>> failures = new HashMap<>();
  private final Deque<Instant> registrations = new ArrayDeque<>();

  @Autowired
  public LoginThrottle(@Value("${app.auth.max-failures:5}") int maxFailures,
                       @Value("${app.auth.max-registrations:30}") int maxRegistrations,
                       @Value("${app.auth.window-minutes:15}") long windowMinutes) {
    this(maxFailures, maxRegistrations, Duration.ofMinutes(windowMinutes), Clock.systemUTC());
  }

  LoginThrottle(int maxFailures, int maxRegistrations, Duration window, Clock clock) {
    this.maxFailures = maxFailures;
    this.maxRegistrations = maxRegistrations;
    this.window = window;
    this.clock = clock;
  }

  /** Before checking the password: 429 if this username had too many wrong passwords lately. */
  public synchronized void checkLogin(String username) {
    Deque<Instant> recent = failures.get(key(username));
    if (recent != null && prune(recent) >= maxFailures) {
      throw tooMany();
    }
  }

  public synchronized void loginFailed(String username) {
    if (failures.size() >= MAX_TRACKED) {
      failures.values().removeIf(d -> prune(d) == 0);
      if (failures.size() >= MAX_TRACKED) {
        failures.clear();
      }
    }
    Deque<Instant> recent = failures.computeIfAbsent(key(username), k -> new ArrayDeque<>());
    recent.addLast(clock.instant());
    if (prune(recent) == maxFailures) {
      log.warn("Too many wrong passwords for one username: refused for {} minutes", window.toMinutes());
    }
  }

  public synchronized void loginSucceeded(String username) {
    failures.remove(key(username));
  }

  /** Counts one registration, or 429 if the site had too many lately. */
  public synchronized void register() {
    if (prune(registrations) >= maxRegistrations) {
      log.warn("Too many registrations: refused for a while");
      throw tooMany();
    }
    registrations.addLast(clock.instant());
  }

  private int prune(Deque<Instant> times) {
    Instant oldest = clock.instant().minus(window);
    while (!times.isEmpty() && times.peekFirst().isBefore(oldest)) {
      times.removeFirst();
    }
    return times.size();
  }

  private static String key(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }

  private static ResponseStatusException tooMany() {
    return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts, try again later");
  }
}
