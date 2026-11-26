package com.rondinella.moneymanageapi.stocks;

import com.rondinella.moneymanageapi.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Prices of the ISINs of the user, kept in their schema (table stock): the graphs read them from the database.
 * Yahoo is asked only when an ISIN has no price yet or its last one is older than {@link #STALE_AFTER}, at most once
 * every {@link #RETRY_AFTER} per ISIN (also when it fails, so an ISIN Yahoo does not know costs nothing on the next
 * opening), all the ISINs at the same time and for at most {@code app.prices.wait-ms} (3 s) per request: a slow download finishes in the
 * background and the graph shows what is already there.
 */
@Service
public class StockPrices {

  static final Duration STALE_AFTER = Duration.ofDays(7);
  static final Duration RETRY_AFTER = Duration.ofHours(6);
  /** made-up ISINs (sample data): no market has them */
  private static final String MADE_UP = "XX";

  private static final Logger log = LoggerFactory.getLogger(StockPrices.class);

  private final StockRepository stocks;
  private final YahooPrices yahoo;
  private final TransactionTemplate transaction;
  private final Clock clock;
  private final Duration wait;
  private final Map<String, Instant> lastAsked = new ConcurrentHashMap<>();
  private final ExecutorService downloads = Executors.newFixedThreadPool(4, r -> {
    Thread t = new Thread(r, "prices");
    t.setDaemon(true);
    return t;
  });

  @PersistenceContext
  private EntityManager entityManager;

  public StockPrices(StockRepository stocks, YahooPrices yahoo, PlatformTransactionManager transactions,
                     @Value("${app.prices.wait-ms:3000}") long waitMillis) {
    this.wait = Duration.ofMillis(waitMillis);
    this.stocks = stocks;
    this.yahoo = yahoo;
    this.transaction = new TransactionTemplate(transactions);
    this.clock = Clock.systemUTC();
  }

  @PreDestroy
  void stop() {
    downloads.shutdownNow();
  }

  /** Weekly prices of the ISIN between the two dates, oldest first, from the database. */
  public List<Stock> between(String isin, Timestamp from, Timestamp to) {
    return stocks.findAllByIsinAndDatetimeBetweenOrderByDatetime(isin, from, to);
  }

  /** Downloads what is missing or old for these ISINs, waiting at most {@code app.prices.wait-ms}. */
  public void refresh(Collection<String> isins) {
    String schema = TenantContext.current();
    Instant now = clock.instant();
    List<CompletableFuture<Void>> jobs = new ArrayList<>();
    for (String isin : isins) {
      if (isin == null || isin.toUpperCase().startsWith(MADE_UP)) {
        continue;
      }
      Timestamp latest = stocks.latestDatetime(isin);
      if (latest != null && latest.toInstant().isAfter(now.minus(STALE_AFTER))) {
        continue;
      }
      // one attempt every RETRY_AFTER per user and ISIN, whoever asks first
      String key = schema + "/" + isin;
      Instant previous = lastAsked.get(key);
      if (previous != null && previous.isAfter(now.minus(RETRY_AFTER))) {
        continue;
      }
      if (previous == null ? lastAsked.putIfAbsent(key, now) != null : !lastAsked.replace(key, previous, now)) {
        continue;
      }
      jobs.add(CompletableFuture.runAsync(() -> TenantContext.runAs(schema, () -> {
        download(isin, latest);
        return null;
      }), downloads));
    }
    if (jobs.isEmpty()) {
      return;
    }
    try {
      CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(wait.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      log.info("Prices still downloading: the graph shows what is there");
    } catch (ExecutionException e) {
      // download() catches everything: nothing to do
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void download(String isin, Timestamp latest) {
    try {
      YahooPrices.Prices prices = yahoo.weekly(isin);
      List<Stock> fresh = prices.weekly().stream()
          .filter(s -> latest == null || s.getDatetime().after(latest))
          .toList();
      transaction.executeWithoutResult(status -> {
        for (Stock s : fresh) {
          entityManager.persist(s);
        }
      });
    } catch (Exception e) {
      // Yahoo down, slow or without this ISIN: the graph keeps the prices already stored (no ISIN in the logs)
      log.warn("Prices not downloaded: {}", e.getMessage());
    }
  }
}
