package com.rondinella.moneymanageapi.assets;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;

/**
 * The rules of what the assets page saves (real estate, other assets, debts). A broken rule is a 400 with a code as
 * its message, which the web app turns into words: NAME_REQUIRED, NAME_TOO_LONG, TEXT_TOO_LONG, AMOUNT_REQUIRED,
 * AMOUNT_RANGE (below 0, more than two decimals or too large), CURRENCY (not an ISO 4217 code), KIND, RATE_RANGE
 * (0 to 100 with at most three decimals), DATE_IN_FUTURE, DATE_RANGE (before 1900 or more than 100 years ahead).
 */
public final class Checks {

  public static final int MAX_NAME = 200;
  public static final int MAX_TEXT = 300;
  /** a thousand billion: more than anyone owns, well inside DECIMAL(19, 2) */
  static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000");
  static final BigDecimal MAX_RATE = new BigDecimal("100");
  static final LocalDate FIRST_DAY = LocalDate.of(1900, 1, 1);

  private Checks() {
  }

  public static ResponseStatusException bad(String code) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
  }

  /** trimmed, not empty, at most {@link #MAX_NAME} characters */
  public static String name(String name) {
    String clean = name == null ? "" : name.strip();
    if (clean.isEmpty()) {
      throw bad("NAME_REQUIRED");
    }
    if (clean.length() > MAX_NAME) {
      throw bad("NAME_TOO_LONG");
    }
    return clean;
  }

  /** optional text: trimmed, null when empty */
  public static String text(String text) {
    String clean = text == null ? "" : text.strip();
    if (clean.length() > MAX_TEXT) {
      throw bad("TEXT_TOO_LONG");
    }
    return clean.isEmpty() ? null : clean;
  }

  public static BigDecimal amount(BigDecimal amount) {
    if (amount == null) {
      throw bad("AMOUNT_REQUIRED");
    }
    return optionalAmount(amount);
  }

  /** 0 or more, at most two decimals */
  public static BigDecimal optionalAmount(BigDecimal amount) {
    if (amount == null) {
      return null;
    }
    if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0 || amount.stripTrailingZeros().scale() > 2) {
      throw bad("AMOUNT_RANGE");
    }
    return amount;
  }

  /** a yearly rate in percent, 0 to 100 with at most three decimals */
  public static BigDecimal rate(BigDecimal rate) {
    if (rate == null) {
      return null;
    }
    if (rate.signum() < 0 || rate.compareTo(MAX_RATE) > 0 || rate.stripTrailingZeros().scale() > 3) {
      throw bad("RATE_RANGE");
    }
    return rate;
  }

  /** an ISO 4217 code (EUR, USD, CHF...), upper case */
  public static String currency(String currency) {
    String code = currency == null ? "" : currency.strip().toUpperCase(Locale.ROOT);
    if (!code.matches("[A-Z]{3}")) {
      throw bad("CURRENCY");
    }
    try {
      Currency.getInstance(code);
    } catch (IllegalArgumentException e) {
      throw bad("CURRENCY");
    }
    return code;
  }

  /** an optional date between 1900 and 100 years from today */
  public static LocalDate date(LocalDate date, LocalDate today) {
    if (date != null && (date.isBefore(FIRST_DAY) || date.isAfter(today.plusYears(100)))) {
      throw bad("DATE_RANGE");
    }
    return date;
  }

  /** an optional date that has already come (an estimate is made today or before) */
  public static LocalDate past(LocalDate date, LocalDate today) {
    if (date != null && date.isAfter(today)) {
      throw bad("DATE_IN_FUTURE");
    }
    return date(date, today);
  }
}
