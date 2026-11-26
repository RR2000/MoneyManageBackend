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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Prices of the ISINs of the user, kept in their schema (table stock): the graphs read them from the database.
 * The market is asked only when an ISIN has no price yet or its last one is older than {@link #STALE_AFTER}, at most
 * once every {@link #RETRY_AFTER} per ISIN (also when it fails), all the ISINs at the same time and for at most
 * {@code app.prices.wait-ms} (3 s) per request: a slow download finishes in the background and the graph shows what
 * is already there. Sources in order: Alpha Vantage (symbol from {@link SymbolResolver}), then Yahoo; prices can also
 * be imported from a CSV ({@link #CSV}).
 */
@Service
public class StockPrices {

  static final Duration STALE_AFTER = Duration.ofDays(7);
  static final Duration RETRY_AFTER = Duration.ofHours(24);
  /** symbol of the prices imported from a CSV */
  public static final String CSV = "CSV";
  /** made-up ISINs (sample data): no market has them */
  private static final String MADE_UP = "XX";

  private static final Logger log = LoggerFactory.getLogger(StockPrices.class);

  private final StockRepository stocks;
  private final InstrumentRepository instruments;
  private final SymbolResolver resolver;
  private final AlphaVantage alphaVantage;
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

  public StockPrices(StockRepository stocks, InstrumentRepository instruments, SymbolResolver resolver,
                     AlphaVantage alphaVantage, YahooPrices yahoo, PlatformTransactionManager transactions,
                     @Value("${app.prices.wait-ms:3000}") long waitMillis) {
    this.stocks = stocks;
    this.instruments = instruments;
    this.resolver = resolver;
    this.alphaVantage = alphaVantage;
    this.yahoo = yahoo;
    this.transaction = new TransactionTemplate(transactions);
    this.wait = Duration.ofMillis(waitMillis);
    this.clock = Clock.systemUTC();
  }

  @PreDestroy
  void stop() {
    downloads.shutdownNow();
  }

  /**
   * Weekly prices of the ISIN between the two dates, oldest first, from the database. When the ISIN has a symbol
   * with prices, only those (and the imported ones): older prices from another source can be in another currency.
   */
  public List<Stock> between(String isin, Timestamp from, Timestamp to) {
    Optional<String> symbol = instruments.findById(isin).map(Instrument::getSymbol)
        .filter(s -> stocks.existsByIsinAndSymbol(isin, s));
    return symbol.isPresent()
        ? stocks.findAllByIsinAndSymbolInAndDatetimeBetweenOrderByDatetime(isin, List.of(symbol.get(), CSV), from, to)
        : stocks.findAllByIsinAndDatetimeBetweenOrderByDatetime(isin, from, to);
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
        download(isin);
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

  /** The next refresh asks the market again for this ISIN (after its symbol changed). */
  public void forget(String isin) {
    lastAsked.remove(TenantContext.current() + "/" + isin);
  }

  private void download(String isin) {
    if (alphaVantage.enabled()) {
      try {
        Optional<Instrument> instrument = resolver.resolve(isin);
        if (instrument.isPresent()) {
          String symbol = instrument.get().getSymbol();
          save(isin, symbol, alphaVantage.weekly(symbol, isin, instrument.get().getCurrency()));
          return;
        }
      } catch (Exception e) {
        // Alpha Vantage down, over the limit or without this ISIN: Yahoo may still have it (no ISIN in the logs)
        log.warn("Prices not downloaded from Alpha Vantage: {}", e.getMessage());
      }
    }
    try {
      YahooPrices.Prices prices = yahoo.weekly(isin);
      save(isin, prices.symbol(), prices.weekly());
    } catch (Exception e) {
      log.warn("Prices not downloaded: {}", e.getMessage());
    }
  }

  /**
   * Keeps the downloaded prices of one symbol: the weeks after the last stored one, and the last stored week again
   * (it may have been an unfinished week, whose price has changed since).
   */
  public void save(String isin, String symbol, List<Stock> weekly) {
    Timestamp latest = stocks.latestDatetime(isin, symbol);
    Timestamp from = latest == null ? null : Timestamp.from(latest.toInstant().minus(Duration.ofDays(7)));
    transaction.executeWithoutResult(status -> {
      if (from != null) {
        stocks.deleteFrom(isin, symbol, from);
      }
      for (Stock s : weekly) {
        if (from == null || !s.getDatetime().before(from)) {
          entityManager.persist(new Stock(symbol, isin, s.getDatetime(), s.getOpen(), s.getLow(), s.getHigh(),
              s.getClose(), s.getCurrency(), s.getVolume()));
        }
      }
    });
  }
}
