package com.rondinella.moneymanageapi.auth.mail;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Brakes on the requests that send an email, in a sliding window:
 * <ul>
 *   <li>per address of the client: over the limit the request gets a 429 (it says nothing about any account);</li>
 *   <li>per email: over the limit nothing more is sent, but the answer stays the same (no enumeration);</li>
 *   <li>for the whole site: protects the relay's daily quota, same answer as above.</li>
 * </ul>
 * In memory: a restart forgets it, enough for one instance.
 */
@Component
public class MailLimits {

  /** more distinct keys than this in one window: the oldest are forgotten (memory stays bounded) */
  private static final int MAX_TRACKED = 10_000;

  private final int perIp;
  private final int perEmail;
  private final int total;
  private final Duration window;
  private final Clock clock;

  private final Map<String, Deque<Instant>> ips = new HashMap<>();
  private final Map<String, Deque<Instant>> emails = new HashMap<>();
  private final Deque<Instant> all = new ArrayDeque<>();

  @Autowired
  public MailLimits(@Value("${app.mail.limit.per-ip:10}") int perIp,
                    @Value("${app.mail.limit.per-email:3}") int perEmail,
                    @Value("${app.mail.limit.total:50}") int total,
                    @Value("${app.mail.limit.window-minutes:60}") long windowMinutes) {
    this(perIp, perEmail, total, Duration.ofMinutes(windowMinutes), Clock.systemUTC());
  }

  MailLimits(int perIp, int perEmail, int total, Duration window, Clock clock) {
    this.perIp = perIp;
    this.perEmail = perEmail;
    this.total = total;
    this.window = window;
    this.clock = clock;
  }

  /** Counts one request from this address; false if it had too many lately. */
  public synchronized boolean allowIp(String ip) {
    return take(ips, ip == null ? "" : ip, perIp);
  }

  /** Counts one mail to this address and one for the site; false if either is over its limit (nothing to send). */
  public synchronized boolean allowMail(String email) {
    Deque<Instant> recent = emails.get(email);
    if (recent != null && prune(recent) >= perEmail) {
      return false;
    }
    if (prune(all) >= total) {
      return false;
    }
    all.addLast(clock.instant());
    return take(emails, email, perEmail);
  }

  private boolean take(Map<String, Deque<Instant>> map, String key, int max) {
    if (map.size() >= MAX_TRACKED && !map.containsKey(key)) {
      map.values().removeIf(d -> prune(d) == 0);
      if (map.size() >= MAX_TRACKED) {
        map.clear();
      }
    }
    Deque<Instant> recent = map.computeIfAbsent(key, k -> new ArrayDeque<>());
    if (prune(recent) >= max) {
      return false;
    }
    recent.addLast(clock.instant());
    return true;
  }

  private int prune(Deque<Instant> times) {
    Instant oldest = clock.instant().minus(window);
    while (!times.isEmpty() && times.peekFirst().isBefore(oldest)) {
      times.removeFirst();
    }
    return times.size();
  }
}
