package com.rondinella.moneymanageapi.stocks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * From an ISIN to its Alpha Vantage symbol: the listings from OpenFIGI (kept for good, for everybody), turned into
 * Alpha Vantage symbols by exchange (London IWDA -> IWDA.LON), then one SYMBOL_SEARCH to see which of them Alpha
 * Vantage has and in which currency. Among those it prefers one in {@link #INVESTMENT_CURRENCY}, then the exchanges in
 * the order of {@link #SUFFIX}. The result goes in the user's instrument table; a symbol set by hand is never
 * replaced, and an ISIN without a symbol is looked up again only after {@link #RETRY_UNRESOLVED}.
 */
@Service
public class SymbolResolver {

  /** the currency of the investments (the broker account): prices in another one are shown with a warning */
  public static final Currency INVESTMENT_CURRENCY = Currency.getInstance("EUR");
  static final Duration RETRY_UNRESOLVED = Duration.ofDays(7);
  /** at most this many SYMBOL_SEARCH per ISIN (Alpha Vantage allows 25 requests a day) */
  private static final int MAX_SEARCHES = 2;

  /** OpenFIGI exchange code -> Alpha Vantage suffix, in order of preference (euro markets first). */
  static final Map<String, String> SUFFIX = new LinkedHashMap<>();

  static {
    SUFFIX.put("IM", ".MIL");  // Borsa Italiana
    SUFFIX.put("NA", ".AMS");  // Euronext Amsterdam
    SUFFIX.put("GY", ".DEX");  // XETRA
    SUFFIX.put("GR", ".DEX");  // Germany, composite
    SUFFIX.put("FP", ".PAR");  // Euronext Paris
    SUFFIX.put("LN", ".LON");  // London
    SUFFIX.put("US", "");      // United States, composite
    SUFFIX.put("UN", "");      // NYSE
    SUFFIX.put("UW", "");      // Nasdaq
    SUFFIX.put("UQ", "");
    SUFFIX.put("UA", "");
    SUFFIX.put("UP", "");
  }

  private final OpenFigi openFigi;
  private final IsinListingRepository listingsCache;
  private final AlphaVantage alphaVantage;
  private final InstrumentRepository instruments;
  private final Clock clock = Clock.systemUTC();
  private final ObjectMapper json = new ObjectMapper();

  public SymbolResolver(OpenFigi openFigi, IsinListingRepository listingsCache, AlphaVantage alphaVantage,
                        InstrumentRepository instruments) {
    this.openFigi = openFigi;
    this.listingsCache = listingsCache;
    this.alphaVantage = alphaVantage;
    this.instruments = instruments;
  }

  /** The instrument of the ISIN with its symbol, looking it up if needed; empty if there is none (yet). */
  public Optional<Instrument> resolve(String isin) throws IOException {
    Optional<Instrument> known = instruments.findById(isin);
    if (known.isPresent() && (known.get().getSymbol() != null || known.get().isManual()
        || known.get().getResolvedAt().toInstant().isAfter(clock.instant().minus(RETRY_UNRESOLVED)))) {
      return known.filter(i -> i.getSymbol() != null);
    }
    Instrument found = lookUp(isin, listings(isin));
    instruments.save(found);
    return Optional.of(found).filter(i -> i.getSymbol() != null);
  }

  /** The listings of the ISIN: from the shared cache, or once from OpenFIGI. */
  List<OpenFigi.Listing> listings(String isin) throws IOException {
    Optional<IsinListing> cached = listingsCache.findById(isin);
    if (cached.isPresent()) {
      return json.readValue(cached.get().getListings(), new TypeReference<>() {
      });
    }
    List<OpenFigi.Listing> listings = openFigi.listings(isin);
    IsinListing entry = new IsinListing();
    entry.setIsin(isin);
    entry.setListings(json.writeValueAsString(listings));
    entry.setFetchedAt(clock.instant());
    listingsCache.save(entry);
    return listings;
  }

  private Instrument lookUp(String isin, List<OpenFigi.Listing> listings) throws IOException {
    Instrument result = new Instrument();
    result.setIsin(isin);
    result.setResolvedAt(Timestamp.from(clock.instant()));
    // candidate symbols in order of preference, and the tickers to search
    Map<String, OpenFigi.Listing> candidates = new LinkedHashMap<>();
    for (Map.Entry<String, String> exchange : SUFFIX.entrySet()) {
      for (OpenFigi.Listing l : listings) {
        if (l.exchange().equals(exchange.getKey())) {
          candidates.putIfAbsent((l.ticker() + exchange.getValue()).toUpperCase(Locale.ROOT), l);
        }
      }
    }
    Set<String> tickers = new LinkedHashSet<>();
    candidates.values().forEach(l -> tickers.add(l.ticker()));
    // every listing Alpha Vantage has among the candidates, searching ticker after ticker until one quotes in euro:
    // the first ticker can be a listing in pence (SWDA in London, GBX) while the euro one has another ticker
    Map<String, AlphaVantage.Match> offered = new LinkedHashMap<>();
    int searches = 0;
    for (String ticker : tickers) {
      if (searches++ >= MAX_SEARCHES || usable(candidates, offered).stream()
          .anyMatch(m -> INVESTMENT_CURRENCY.equals(m.currency()))) {
        break;
      }
      for (AlphaVantage.Match m : alphaVantage.search(ticker)) {
        offered.putIfAbsent(m.symbol().toUpperCase(Locale.ROOT), m);
      }
    }
    List<AlphaVantage.Match> usable = usable(candidates, offered);
    // euro first, then a known currency (an unknown one is usually pence), then anything
    Optional<AlphaVantage.Match> best = usable.stream()
        .filter(m -> INVESTMENT_CURRENCY.equals(m.currency())).findFirst()
        .or(() -> usable.stream().filter(m -> m.currency() != null).findFirst())
        .or(() -> usable.stream().findFirst());
    if (best.isPresent()) {
      result.setSymbol(best.get().symbol());
      result.setCurrency(best.get().currency());
      result.setName(trim(best.get().name()));
      return result;
    }
    if (!listings.isEmpty()) {
      result.setName(trim(listings.getFirst().name()));
    }
    return result;
  }

  /** The candidates Alpha Vantage offers, in order of preference. */
  private static List<AlphaVantage.Match> usable(Map<String, OpenFigi.Listing> candidates,
                                                 Map<String, AlphaVantage.Match> offered) {
    List<AlphaVantage.Match> usable = new ArrayList<>();
    for (String symbol : candidates.keySet()) {
      if (offered.containsKey(symbol)) {
        usable.add(offered.get(symbol));
      }
    }
    return usable;
  }

  private static String trim(String name) {
    return name == null || name.length() <= 200 ? name : name.substring(0, 200);
  }
}
