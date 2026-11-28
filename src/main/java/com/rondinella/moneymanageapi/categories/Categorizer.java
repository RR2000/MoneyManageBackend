package com.rondinella.moneymanageapi.categories;

import com.rondinella.moneymanageapi.banktransactions.BankTransaction;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * The rules on new movements, before they are saved: from a file, typed in, from the bank (import and sync). A movement
 * that already has a category (or was given one by hand) keeps it. The movements already there change only with
 * "apply to all" ({@link Categories#apply}).
 */
@Component
public class Categorizer {

  private final CategoryRuleRepository rules;

  public Categorizer(CategoryRuleRepository rules) {
    this.rules = rules;
  }

  /** gives the category of the first rule that matches to every movement without one; returns how many got one */
  public int categorize(Collection<BankTransaction> movements) {
    if (movements.isEmpty()) {
      return 0;
    }
    List<CategoryRule> ordered = Rules.ordered(rules.findAll());
    if (ordered.isEmpty()) {
      return 0;
    }
    int given = 0;
    for (BankTransaction t : movements) {
      if (t.getCategoryId() != null || t.getCategorySource() != null) {
        continue;
      }
      CategoryRule rule = Rules.first(ordered, t);
      if (rule != null) {
        t.setCategoryId(rule.getCategoryId());
        t.setCategorySource(BankTransaction.CategorySource.RULE);
        given++;
      }
    }
    return given;
  }
}
