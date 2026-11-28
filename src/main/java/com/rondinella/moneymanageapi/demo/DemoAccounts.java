package com.rondinella.moneymanageapi.demo;

import com.rondinella.moneymanageapi.auth.AuthService;
import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.tenant.TenantContext;
import com.rondinella.moneymanageapi.tenant.TenantMigrations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The demo sandboxes: "Prova la demo" makes an account of its own for every visitor (username demo-&lt;random&gt;, no
 * email, no password anybody knows, the demo flag and an end {@link DemoSettings#lifetime()} later) with the sample
 * data, and answers its token like a login. What reaches outside money (bank, mail, the site's sign-on, market prices)
 * is refused for it (DemoGuard, StockPrices); everything else works, inside its own schema.
 * <p>
 * Limits: {@link DemoSettings#perIp()} new sandboxes an hour per client address (in memory: a restart forgets it) and
 * {@link DemoSettings#maxActive()} open at the same time (counted in the database); over them 429 with the code
 * DEMO_LIMIT_IP or DEMO_FULL. Every hour the sandboxes past their end are removed with their schema; the removal
 * only ever touches rows with the demo flag AND the demo- prefix, checked by the same statement that deletes them.
 */
@Service
public class DemoAccounts {

  public static final String LIMIT_IP = "DEMO_LIMIT_IP";
  public static final String FULL = "DEMO_FULL";
  private static final Logger log = LoggerFactory.getLogger(DemoAccounts.class);
  private static final Duration IP_WINDOW = Duration.ofHours(1);
  /** more addresses than this in one window: the oldest are forgotten (memory stays bounded) */
  private static final int MAX_TRACKED = 10_000;
  /** no 0/o, 1/l: the name can be read aloud */
  private static final char[] ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789".toCharArray();
  private static final String USERS = TenantContext.SHARED + ".users";

  private final DemoSettings settings;
  private final AuthService auth;
  private final UserRepository users;
  private final SampleData sampleData;
  private final JdbcTemplate jdbc;
  private final TenantMigrations migrations;
  private final DemoSchemas schemas;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Deque<Instant>> byIp = new HashMap<>();

  @Autowired
  public DemoAccounts(DemoSettings settings, AuthService auth, UserRepository users, SampleData sampleData,
                      JdbcTemplate jdbc, TenantMigrations migrations, DemoSchemas schemas,
                      PlatformTransactionManager transactions) {
    this(settings, auth, users, sampleData, jdbc, migrations, schemas, transactions, Clock.systemUTC());
  }

  DemoAccounts(DemoSettings settings, AuthService auth, UserRepository users, SampleData sampleData, JdbcTemplate jdbc,
               TenantMigrations migrations, DemoSchemas schemas, PlatformTransactionManager transactions, Clock clock) {
    this.settings = settings;
    this.auth = auth;
    this.users = users;
    this.sampleData = sampleData;
    this.jdbc = jdbc;
    this.migrations = migrations;
    this.schemas = schemas;
    this.transaction = new TransactionTemplate(transactions);
    this.clock = clock;
  }

  /** A sandbox refused by a limit: 429 with the code (DEMO_LIMIT_IP, DEMO_FULL). */
  public static final class Refused extends RuntimeException {
    private final String code;

    Refused(String code) {
      super(code, null, false, false);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  /**
   * A new sandbox for a visitor at this address: the account, its schema and the sample data; the token to use it.
   * One at a time (a few seconds each), so two visitors together cannot both take the last place.
   */
  public synchronized String create(String clientIp) {
    Instant now = clock.instant();
    Deque<Instant> recent = recent(clientIp, now);
    if (recent.size() >= settings.perIp()) {
      throw new Refused(LIMIT_IP);
    }
    if (users.countByDemoTrueAndExpiresAtAfter(now) >= settings.maxActive()) {
      log.warn("Demo: {} sandboxes open, the limit: no new one for now", settings.maxActive());
      throw new Refused(FULL);
    }
    // counted before the work: a sandbox that fails half way still took a place from this address
    recent.addLast(now);
    User user = auth.createDemoAccount(freeName(), now.plus(settings.lifetime()));
    try {
      TenantContext.runAs(TenantContext.schemaOf(user.getId()), () -> {
        sampleData.load();
        return null;
      });
    } catch (RuntimeException e) {
      // no sandbox without its data: removed now, not left to the cleanup
      remove(user.getId());
      throw e;
    }
    return auth.tokenFor(user.getUsername());
  }

  /** Every hour: the sandboxes past their end, with their schema. How many were removed. */
  @Scheduled(cron = "${app.demo.cleanup-cron:0 7 * * * *}", zone = "Europe/Rome")
  public int removeExpired() {
    List<Long> expired = jdbc.queryForList(
        "SELECT id FROM " + USERS + " WHERE demo AND username LIKE 'demo-%' AND expires_at < ?",
        Long.class, Timestamp.from(clock.instant()));
    int removed = 0;
    for (Long id : expired) {
      try {
        if (remove(id)) {
          removed++;
        }
      } catch (RuntimeException e) {
        log.error("Demo: a sandbox was not removed ({}), next hour again", e.getClass().getName());
      }
    }
    if (removed > 0) {
      log.info("Demo: {} sandboxes past their end removed", removed);
    }
    return removed;
  }

  /**
   * Removes one sandbox: its row (email links and bank states go with it, ON DELETE CASCADE) and its schema, in one
   * transaction. The delete itself requires the demo flag and the demo- prefix: for any other account it deletes
   * nothing, and then the schema is not touched. False if nothing was removed.
   */
  boolean remove(long id) {
    String schema = TenantContext.schemaOf(id);
    Boolean done = transaction.execute(status -> {
      int deleted = jdbc.update("DELETE FROM " + USERS + " WHERE id = ? AND demo AND username LIKE 'demo-%'", id);
      if (deleted != 1) {
        return false;
      }
      // the name is u + the number of the row just deleted: nothing a visitor typed
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      return true;
    });
    if (Boolean.TRUE.equals(done)) {
      migrations.forget(schema);
      schemas.forget(schema);
      return true;
    }
    return false;
  }

  private String freeName() {
    for (int attempt = 0; attempt < 10; attempt++) {
      StringBuilder name = new StringBuilder(AuthService.DEMO_PREFIX);
      for (int i = 0; i < 10; i++) {
        name.append(ALPHABET[random.nextInt(ALPHABET.length)]);
      }
      if (!users.existsByUsernameIgnoreCase(name.toString())) {
        return name.toString();
      }
    }
    throw new IllegalStateException("No free demo name");
  }

  private Deque<Instant> recent(String clientIp, Instant now) {
    if (byIp.size() >= MAX_TRACKED) {
      byIp.values().removeIf(d -> prune(d, now) == 0);
      if (byIp.size() >= MAX_TRACKED) {
        byIp.clear();
      }
    }
    Deque<Instant> recent = byIp.computeIfAbsent(clientIp == null ? "" : clientIp, k -> new ArrayDeque<>());
    prune(recent, now);
    return recent;
  }

  private static int prune(Deque<Instant> times, Instant now) {
    Instant oldest = now.minus(IP_WINDOW);
    while (!times.isEmpty() && !times.peekFirst().isAfter(oldest)) {
      times.removeFirst();
    }
    return times.size();
  }
}
