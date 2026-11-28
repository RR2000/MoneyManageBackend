package com.rondinella.moneymanageapi.categories;

import com.rondinella.moneymanageapi.banktransactions.BankTransaction;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * How a rule matches a movement: the description contains the text of the rule, without caring about upper or lower
 * case and about repeated spaces; money out or in when the rule says so (an amount of 0 is neither), and the account
 * when the rule names one. When more rules match, the most precise wins: the longest text, then the one of an account,
 * then the one of a direction, then the oldest.
 */
public final class Rules {

  public static final String IN = "IN";
  public static final String OUT = "OUT";

  private Rules() {
  }

  /** lower case, spaces in a row as one, no spaces around */
  public static String normalize(String text) {
    return text == null ? "" : text.strip().replaceAll("\s+", " ").toLowerCase(Locale.ROOT);
  }

  /** the rules, the most precise first: {@link #first} takes the first that matches */
  public static List<CategoryRule> ordered(List<CategoryRule> rules) {
    return rules.stream().sorted(Comparator
        .comparing((CategoryRule r) -> -normalize(r.getPattern()).length())
        .thenComparing(r -> r.getAccount() == null ? 1 : 0)
        .thenComparing(r -> r.getDirection() == null ? 1 : 0)
        .thenComparing(CategoryRule::getId, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
  }

  public static boolean matches(CategoryRule rule, String account, String description, BigDecimal amount) {
    String pattern = normalize(rule.getPattern());
    if (pattern.isEmpty() || !normalize(description).contains(pattern)) {
      return false;
    }
    if (rule.getAccount() != null && !rule.getAccount().equals(account)) {
      return false;
    }
    if (rule.getDirection() != null) {
      int sign = amount == null ? 0 : amount.signum();
      return OUT.equals(rule.getDirection()) ? sign < 0 : sign > 0;
    }
    return true;
  }

  /** the first of {@code ordered} that matches the movement, null if none */
  public static CategoryRule first(List<CategoryRule> ordered, String account, String description, BigDecimal amount) {
    for (CategoryRule rule : ordered) {
      if (matches(rule, account, description, amount)) {
        return rule;
      }
    }
    return null;
  }

  public static CategoryRule first(List<CategoryRule> ordered, BankTransaction t) {
    return first(ordered, t.getAccount(), t.getDescription(), t.getAmount());
  }
}
