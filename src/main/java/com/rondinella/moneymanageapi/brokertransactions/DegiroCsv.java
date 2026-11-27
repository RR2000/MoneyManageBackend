package com.rondinella.moneymanageapi.brokertransactions;

import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The two Degiro exports, read by column name, never by position: "Transazioni"/"Transactions" (Transactions.csv,
 * the executions) and "Estratto conto"/"Account statement" (Account.csv, the cash movements). Headers in Italian and
 * English (the formats checked), and the German, Dutch, Spanish and French names of the same columns. A currency is
 * the unnamed column next to its amount (Degiro puts the header on one of the two): whichever of the two holds a
 * three-letter code. Nothing is converted.
 */
public final class DegiroCsv {

  /** One execution. currency: of the price; feeCurrency: of the fee; null when the file does not say. */
  public record Trade(Timestamp datetime, String product, String isin, BigDecimal quantity, BigDecimal price,
                      String currency, BigDecimal fee, String feeCurrency, String orderId) {
  }

  /** One cash movement of the account statement. */
  public record CashMovement(Timestamp datetime, String description, BigDecimal amount, String currency) {
  }

  /** Columns of the two exports: the names it, en, de, nl, es, fr, normalized (lower case, no accents, letters only). */
  private static final Set<String> DATE = Set.of("data", "date", "datum", "fecha");
  private static final Set<String> TIME = Set.of("ora", "time", "uhrzeit", "tijd", "hora", "heure");
  private static final Set<String> VALUE_DATE = Set.of("datavalore", "valuedate", "valutadatum", "fechavalor",
      "datedevaleur", "datevaleur");
  private static final Set<String> PRODUCT = Set.of("prodotto", "product", "produkt", "producto", "produit");
  private static final Set<String> ISIN = Set.of("isin", "codiceisin");
  private static final Set<String> QUANTITY = Set.of("quantita", "quantity", "anzahl", "aantal", "numero", "cantidad",
      "quantite", "nombre");
  private static final Set<String> PRICE = Set.of("quotazione", "prezzo", "price", "kurs", "koers", "precio", "cours",
      "prix");
  private static final List<String> FEE_PREFIXES = List.of("commissioni", "costiditransazione", "transactionandor",
      "transactioncosts", "transactionfees", "transaktionskosten", "transactiekosten", "costesdetransaccion",
      "fraisdetransaction");
  private static final Set<String> DESCRIPTION = Set.of("descrizione", "description", "beschreibung", "omschrijving",
      "descripcion");
  private static final Set<String> CHANGE = Set.of("variazione", "change", "anderung", "mutatie", "variacion",
      "mouvement", "mouvements");
  private static final Set<String> ORDER_ID = Set.of("idordine", "orderid", "idorden", "idordre", "iddellordine");

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d-M-uuuu");
  private static final DateTimeFormatter DAY_SLASH = DateTimeFormatter.ofPattern("d/M/uuuu");

  private DegiroCsv() {
  }

  public static List<Trade> trades(String csv) {
    Table t = table(csv);
    int date = t.column(DATE);
    int time = t.column(TIME);
    int product = t.column(PRODUCT);
    int isin = t.column(ISIN);
    int quantity = t.column(QUANTITY);
    int price = t.column(PRICE);
    int fee = t.prefixColumn(FEE_PREFIXES);
    int orderId = t.optionalColumn(ORDER_ID);
    if (date < 0 || isin < 0 || quantity < 0 || price < 0) {
      throw new IllegalArgumentException("not a Degiro transactions export");
    }
    List<Trade> trades = new ArrayList<>();
    for (String[] row : t.rows()) {
      String isinValue = cell(row, isin);
      if (isinValue.isEmpty() || cell(row, date).isEmpty()) {
        continue;
      }
      Amount p = t.amount(row, price);
      Amount f = fee < 0 ? new Amount(null, null) : t.amount(row, fee);
      String order = orderId < 0 ? "" : cell(row, orderId);
      trades.add(new Trade(datetime(cell(row, date), time < 0 ? "" : cell(row, time)), product < 0 ? "" : cell(row, product),
          isinValue, number(cell(row, quantity)), p.value(), p.currency(),
          f.value() == null ? BigDecimal.ZERO : f.value().negate(), f.currency(),
          order.isEmpty() || order.length() > 64 ? null : order));
    }
    return trades;
  }

  /**
   * The account statement. datetime is the value date with the time (as money always read it, so the rows imported
   * before are found again); without a value date, the date. Rows without an amount (the exchange rate lines) and the
   * "Degiro Cash Sweep Transfer" are left out.
   */
  public static List<CashMovement> account(String csv) {
    Table t = table(csv);
    int date = t.column(DATE);
    int time = t.column(TIME);
    int valueDate = t.optionalColumn(VALUE_DATE);
    int description = t.column(DESCRIPTION);
    int change = t.column(CHANGE);
    if ((date < 0 && valueDate < 0) || description < 0 || change < 0) {
      throw new IllegalArgumentException("not a Degiro account export");
    }
    List<CashMovement> movements = new ArrayList<>();
    for (String[] row : t.rows()) {
      Amount a = t.amount(row, change);
      String text = cell(row, description);
      if (a.value() == null || text.equals("Degiro Cash Sweep Transfer")) {
        continue;
      }
      String day = valueDate >= 0 && !cell(row, valueDate).isEmpty() ? cell(row, valueDate) : cell(row, date);
      movements.add(new CashMovement(datetime(day, time < 0 ? "" : cell(row, time)), text, a.value(),
          a.currency() == null ? "EUR" : a.currency()));
    }
    return movements;
  }

  private record Amount(BigDecimal value, String currency) {
  }

  private record Table(String[] headers, List<String[]> rows) {

    int column(Set<String> names) {
      for (int i = 0; i < headers.length; i++) {
        if (names.contains(headers[i])) {
          return i;
        }
      }
      return -1;
    }

    int optionalColumn(Set<String> names) {
      return column(names);
    }

    int prefixColumn(List<String> prefixes) {
      for (int i = 0; i < headers.length; i++) {
        for (String prefix : prefixes) {
          if (!headers[i].isEmpty() && headers[i].startsWith(prefix)) {
            return i;
          }
        }
      }
      return -1;
    }

    /** The amount under a header and its currency in the unnamed column next to it, in either order. */
    Amount amount(String[] row, int column) {
      String here = cell(row, column);
      boolean nextUnnamed = column + 1 < headers.length && headers[column + 1].isEmpty();
      String next = nextUnnamed ? cell(row, column + 1) : "";
      if (currency(here) != null) {
        return new Amount(next.isEmpty() ? null : number(next), currency(here));
      }
      return new Amount(here.isEmpty() ? null : number(here), currency(next));
    }
  }

  private static Table table(String csv) {
    String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
    int newline = text.indexOf('\n');
    String first = newline < 0 ? text : text.substring(0, newline);
    // Degiro writes commas; some spreadsheets save it again with semicolons
    char separator = first.chars().filter(c -> c == ';').count() > first.chars().filter(c -> c == ',').count() ? ';' : ',';
    try (CSVReader reader = new CSVReaderBuilder(new StringReader(text))
        .withCSVParser(new CSVParserBuilder().withSeparator(separator).build()).build()) {
      List<String[]> all = reader.readAll();
      if (all.isEmpty()) {
        throw new IllegalArgumentException("empty");
      }
      String[] headers = all.getFirst();
      for (int i = 0; i < headers.length; i++) {
        headers[i] = normalize(headers[i]);
      }
      List<String[]> rows = new ArrayList<>();
      for (String[] row : all.subList(1, all.size())) {
        if (row.length > 1 || (row.length == 1 && !row[0].isBlank())) {
          rows.add(row);
        }
      }
      return new Table(headers, rows);
    } catch (IOException | CsvException e) {
      throw new IllegalArgumentException("unreadable CSV", e);
    }
  }

  static String normalize(String header) {
    String plain = Normalizer.normalize(header == null ? "" : header, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
  }

  private static String cell(String[] row, int i) {
    return i >= 0 && i < row.length && row[i] != null ? row[i].trim() : "";
  }

  private static String currency(String s) {
    return s != null && s.matches("[A-Z]{3}") ? s : null;
  }

  /** "1234.5", "1234,5", "1.234,50", "1,234.50", "-2": the last of comma and dot is the decimal separator. */
  static BigDecimal number(String s) {
    String v = s.replace(" ", "").replace(" ", "").replace("'", "");
    int comma = v.lastIndexOf(',');
    int dot = v.lastIndexOf('.');
    if (comma > dot) {
      v = v.replace(".", "").replace(',', '.');
    } else if (dot > comma && comma >= 0) {
      v = v.replace(",", "");
    }
    return new BigDecimal(v);
  }

  private static Timestamp datetime(String day, String time) {
    LocalDate date;
    try {
      date = LocalDate.parse(day, day.contains("/") ? DAY_SLASH : DAY);
    } catch (RuntimeException e) {
      date = LocalDate.parse(day); // ISO, a spreadsheet saved it again
    }
    LocalTime at = time.isEmpty() ? LocalTime.MIDNIGHT : LocalTime.parse(time.length() == 4 ? "0" + time : time);
    return Timestamp.valueOf(LocalDateTime.of(date, at));
  }
}
