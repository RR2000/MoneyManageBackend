package com.rondinella.moneymanageapi.stocks;

import com.rondinella.moneymanageapi.brokertransactions.BrokerTransaction;
import com.rondinella.moneymanageapi.brokertransactions.BrokerTransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The investments page: for every ISIN of the user where its prices come from, the last price and the currency it
 * quotes in (with a warning when it is not the one of the investments). The symbol can be set by hand, after a
 * preview of its last price, and prices can be imported from a CSV (date, price) when no source has them.
 */
@Service
public class Instruments {

  public static final int MAX_CSV_LINES = 20_000;
  private static final Duration PREVIEW_KEPT = Duration.ofHours(1);
  private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9.^=-]{1,30}");
  private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
      DateTimeFormatter.ofPattern("dd/MM/yyyy"), DateTimeFormatter.ofPattern("dd-MM-yyyy"),
      DateTimeFormatter.ofPattern("dd.MM.yyyy"));

  /** One ISIN on the page. */
  public record View(String isin, String name, String symbol, boolean manual, String source, String quoteCurrency,
                     String investmentCurrency, boolean currencyWarning, BigDecimal lastPrice, LocalDate lastDate,
                     long prices) {
  }

  /** What a symbol would give: its last price and its currency. */
  public record Preview(String symbol, String name, String currency, boolean currencyWarning, BigDecimal lastPrice,
                        LocalDate lastDate, int weeks) {
  }

  private record Fetched(Preview preview, Currency currency, List<Stock> weekly, Instant at) {
  }

  private final BrokerTransactionRepository transactions;
  private final InstrumentRepository instruments;
  private final StockRepository stocks;
  private final StockPrices prices;
  private final AlphaVantage alphaVantage;
  private final TransactionTemplate transaction;
  private final Clock clock = Clock.systemUTC();
  /** previews by symbol, so that saving the symbol right after does not ask Alpha Vantage again */
  private final Map<String, Fetched> previews = new ConcurrentHashMap<>();

  public Instruments(BrokerTransactionRepository transactions, InstrumentRepository instruments, StockRepository stocks,
                     StockPrices prices, AlphaVantage alphaVantage, PlatformTransactionManager transactionManager) {
    this.transactions = transactions;
    this.instruments = instruments;
    this.stocks = stocks;
    this.prices = prices;
    this.alphaVantage = alphaVantage;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public boolean alphaVantageEnabled() {
    return alphaVantage.enabled();
  }

  public int alphaVantageLeftToday() {
    return alphaVantage.leftToday();
  }

  public List<View> list() {
    List<View> result = new ArrayList<>();
    for (String isin : transactions.findDistinctIsin()) {
      result.add(view(isin));
    }
    return result;
  }

  public View view(String isin) {
    Optional<Instrument> instrument = instruments.findById(isin);
    String symbol = instrument.map(Instrument::getSymbol).orElse(null);
    Stock last = symbol != null && stocks.existsByIsinAndSymbol(isin, symbol)
        ? stocks.findFirstByIsinAndSymbolInOrderByDatetimeDesc(isin, List.of(symbol, StockPrices.CSV))
        : stocks.findFirstByIsinOrderByDatetimeDesc(isin);
    Currency quote = last != null && StockPrices.CSV.equals(last.getSymbol()) ? SymbolResolver.INVESTMENT_CURRENCY
        : instrument.map(Instrument::getCurrency).orElse(last == null ? null : last.getCurrency());
    String source = last == null ? "nessuna"
        : StockPrices.CSV.equals(last.getSymbol()) ? "csv"
        : last.getSymbol().equals(symbol) ? "alphavantage"
        : isin.toUpperCase(Locale.ROOT).startsWith("XX") ? "esempio" : "yahoo";
    String name = instrument.map(Instrument::getName).filter(n -> !n.isBlank())
        .orElseGet(() -> transactions.findByIsinOrderByDatetime(isin).stream().map(BrokerTransaction::getDescription)
            .filter(d -> d != null && !d.isBlank()).findFirst().orElse(""));
    return new View(isin, name, symbol, instrument.map(Instrument::isManual).orElse(false), source,
        quote == null ? null : quote.getCurrencyCode(), SymbolResolver.INVESTMENT_CURRENCY.getCurrencyCode(),
        warning(quote) || (quote == null && last != null), last == null ? null : last.getClose(),
        last == null ? null : last.getDatetime().toLocalDateTime().toLocalDate(), stocks.countByIsin(isin));
  }

  /** The last price of a symbol on Alpha Vantage, and its currency (two requests, kept for an hour). */
  public Preview preview(String rawSymbol) {
    return fetch(validSymbol(rawSymbol)).preview();
  }

  /** The symbol chosen by hand (empty: back to the automatic one); its prices replace those of other sources. */
  public View setSymbol(String isin, String rawSymbol) {
    requireOwn(isin);
    String symbol = rawSymbol == null ? "" : rawSymbol.trim();
    if (symbol.isEmpty()) {
      instruments.deleteById(isin);
      prices.forget(isin);
      return view(isin);
    }
    Fetched fetched = fetch(validSymbol(symbol));
    String chosen = fetched.preview().symbol();
    transaction.executeWithoutResult(status -> {
      Instrument instrument = instruments.findById(isin).orElseGet(Instrument::new);
      instrument.setIsin(isin);
      instrument.setSymbol(chosen);
      instrument.setCurrency(fetched.currency());
      instrument.setName(fetched.preview().name().isBlank() ? instrument.getName() : fetched.preview().name());
      instrument.setManual(true);
      instrument.setResolvedAt(Timestamp.from(clock.instant()));
      instruments.save(instrument);
      stocks.deleteOtherSymbols(isin, List.of(chosen, StockPrices.CSV));
    });
    prices.save(isin, chosen, fetched.weekly());
    prices.forget(isin);
    return view(isin);
  }

  /**
   * Prices from a CSV: one line per date, "date,price" or "date;price" (dates 2024-01-31, 31/01/2024, 31-01-2024 or
   * 31.01.2024; decimals with a dot, or a comma with ";"), a header line allowed. They count as prices in
   * {@link SymbolResolver#INVESTMENT_CURRENCY} and replace imported prices of the same dates.
   */
  public int importCsv(String isin, String csv) {
    requireOwn(isin);
    if (csv == null || csv.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Il file e' vuoto");
    }
    String[] lines = csv.split("\\r?\\n");
    if (lines.length > MAX_CSV_LINES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Al massimo " + MAX_CSV_LINES + " righe");
    }
    TreeMap<LocalDate, BigDecimal> byDate = new TreeMap<>();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim().replace("\"", "");
      if (line.isEmpty()) {
        continue;
      }
      String[] cells = line.contains(";") ? line.split(";") : line.split(",");
      LocalDate date = cells.length >= 2 ? date(cells[0].trim()) : null;
      BigDecimal price = cells.length >= 2 ? price(cells[1].trim()) : null;
      if (date == null || price == null || price.signum() <= 0) {
        if (i == 0) {
          continue; // header
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Riga " + (i + 1) + " non valida: servono data e prezzo (es. 2024-01-31,80.5)");
      }
      byDate.put(date, price);
    }
    if (byDate.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nessun prezzo nel file");
    }
    Currency eur = SymbolResolver.INVESTMENT_CURRENCY;
    transaction.executeWithoutResult(status -> {
      stocks.deleteBetween(isin, StockPrices.CSV, Timestamp.valueOf(byDate.firstKey().atStartOfDay()),
          Timestamp.valueOf(byDate.lastKey().atStartOfDay()));
      List<Stock> rows = new ArrayList<>();
      byDate.forEach((d, p) -> rows.add(new Stock(StockPrices.CSV, isin, Timestamp.valueOf(d.atStartOfDay()),
          p, p, p, p, eur, null)));
      stocks.saveAll(rows);
    });
    return byDate.size();
  }

  private Fetched fetch(String symbol) {
    if (!alphaVantage.enabled()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Alpha Vantage non e' configurato: importa i prezzi da un CSV");
    }
    String key = symbol.toUpperCase(Locale.ROOT);
    Fetched cached = previews.get(key);
    if (cached != null && cached.at().isAfter(clock.instant().minus(PREVIEW_KEPT))) {
      return cached;
    }
    try {
      AlphaVantage.Match match = alphaVantage.search(symbol).stream()
          .filter(m -> m.symbol().equalsIgnoreCase(symbol)).findFirst().orElse(null);
      Currency currency = match == null ? null : match.currency();
      List<Stock> weekly = alphaVantage.weekly(match == null ? symbol : match.symbol(), "", currency);
      if (weekly.isEmpty()) {
        throw new IOException("no prices");
      }
      Stock last = weekly.getLast();
      Preview preview = new Preview(match == null ? symbol : match.symbol(), match == null ? "" : match.name(),
          currency == null ? null : currency.getCurrencyCode(), currency == null || warning(currency), last.getClose(),
          last.getDatetime().toLocalDateTime().toLocalDate(), weekly.size());
      Fetched fetched = new Fetched(preview, currency, weekly, clock.instant());
      previews.put(key, fetched);
      return fetched;
    } catch (IOException e) {
      String message = e.getMessage() != null && e.getMessage().contains("limit")
          ? "Limite giornaliero di Alpha Vantage raggiunto: riprova domani o importa un CSV"
          : "Alpha Vantage non ha prezzi per questo simbolo";
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
  }

  private void requireOwn(String isin) {
    if (isin == null || !transactions.findDistinctIsin().contains(isin)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nessun investimento con questo ISIN");
    }
  }

  private static String validSymbol(String symbol) {
    String s = symbol == null ? "" : symbol.trim();
    if (!SYMBOL.matcher(s).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Simbolo non valido (es. IWDA.AMS)");
    }
    return s.toUpperCase(Locale.ROOT);
  }

  private static boolean warning(Currency quote) {
    return quote != null && !SymbolResolver.INVESTMENT_CURRENCY.equals(quote);
  }

  private static LocalDate date(String s) {
    for (DateTimeFormatter f : DATES) {
      try {
        return LocalDate.parse(s, f);
      } catch (DateTimeParseException e) {
        // next format
      }
    }
    return null;
  }

  private static BigDecimal price(String s) {
    try {
      return new BigDecimal(s.replace(" ", "").replace(",", "."));
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
