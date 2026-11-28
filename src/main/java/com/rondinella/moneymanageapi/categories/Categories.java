package com.rondinella.moneymanageapi.categories;

import com.rondinella.moneymanageapi.assets.Checks;
import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import com.rondinella.moneymanageapi.banktransactions.BankTransaction;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Categories of the movements and the rules that give them: list, create, change, delete; the category of one movement
 * chosen by hand (optionally with a new rule from it); "apply to all", with a preview of how many movements change.
 * A refused request is a 400 with a code: NAME_REQUIRED, NAME_TOO_LONG (60), NAME_TAKEN, TOO_MANY (200 categories,
 * 1000 rules), PATTERN_REQUIRED, PATTERN_TOO_SHORT (2), PATTERN_TOO_LONG (100), DIRECTION, ACCOUNT, CATEGORY,
 * RULE_EXISTS; an id that is not there is a 404.
 */
@Service
public class Categories {

  static final int MAX_NAME = 60;
  static final int MIN_PATTERN = 2;
  static final int MAX_PATTERN = 100;
  static final int MAX_CATEGORIES = 200;
  static final int MAX_RULES = 1000;

  public record CategoryRequest(String name, Boolean fixed) {
  }

  /** {@code direction}: IN, OUT or empty; {@code accountId}: the id of a money account (its page) or empty */
  public record RuleRequest(Long categoryId, String pattern, String direction, String accountId) {
  }

  public record CategoryView(long id, String name, boolean fixed, long movements, int rules) {
  }

  public record RuleView(long id, long categoryId, String pattern, String direction, String accountId, String account) {
  }

  public record Overview(List<CategoryView> categories, List<RuleView> rules, long uncategorized) {
  }

  /**
   * What "apply to all" does (or would do): movements that get a category ({@code categorized}), that get another one
   * ({@code changed}), that lose the one a rule had given ({@code cleared}, no rule matches any more), and the ones
   * chosen by hand, which stay as they are ({@code manualKept}); {@code total}: the three changes together.
   */
  public record Applied(boolean dryRun, int categorized, int changed, int cleared, int total, long manualKept) {
  }

  /** the rule to create from a movement: its text (the description, or a part of it), direction, only its account */
  public record NewRule(String pattern, String direction, Boolean onlyThisAccount) {
  }

  /** {@code categoryId} null: no category; {@code rule}: also a rule for the movements like this one */
  public record AssignRequest(Long categoryId, NewRule rule) {
  }

  /** the movement now, the rule created (or null) and what "apply to all" would change after it */
  public record Assigned(long id, Long categoryId, String category, String categorySource, RuleView rule, Applied preview) {
  }

  private final CategoryRepository categories;
  private final CategoryRuleRepository rules;
  private final BankTransactionRepository transactions;
  private final AccountPages pages;
  private final EntityManager em;

  public Categories(CategoryRepository categories, CategoryRuleRepository rules, BankTransactionRepository transactions,
                    AccountPages pages, EntityManager em) {
    this.categories = categories;
    this.rules = rules;
    this.transactions = transactions;
    this.pages = pages;
    this.em = em;
  }

  @Transactional(readOnly = true)
  public Overview overview() {
    Map<Long, Long> counts = new HashMap<>();
    for (Object[] row : em.createQuery("SELECT t.categoryId, count(t) FROM BankTransaction t "
        + "WHERE t.categoryId IS NOT NULL GROUP BY t.categoryId", Object[].class).getResultList()) {
      counts.put((Long) row[0], (Long) row[1]);
    }
    List<CategoryRule> all = rules.findAll();
    Map<Long, Integer> ruleCounts = new HashMap<>();
    all.forEach(r -> ruleCounts.merge(r.getCategoryId(), 1, Integer::sum));
    List<CategoryView> views = categories.findAll().stream()
        .sorted(Comparator.comparing((Category c) -> c.getName().toLowerCase(Locale.ROOT)).thenComparing(Category::getId))
        .map(c -> new CategoryView(c.getId(), c.getName(), c.isFixed(), counts.getOrDefault(c.getId(), 0L),
            ruleCounts.getOrDefault(c.getId(), 0)))
        .toList();
    List<RuleView> ruleViews = all.stream()
        .sorted(Comparator.comparing((CategoryRule r) -> Rules.normalize(r.getPattern())).thenComparing(CategoryRule::getId))
        .map(Categories::view).toList();
    return new Overview(views, ruleViews, uncategorized());
  }

  /** movements without a category, of every account */
  @Transactional(readOnly = true)
  public long uncategorized() {
    return em.createQuery("SELECT count(t) FROM BankTransaction t WHERE t.categoryId IS NULL", Long.class)
        .getSingleResult();
  }

  @Transactional
  public CategoryView create(CategoryRequest request) {
    if (categories.count() >= MAX_CATEGORIES) {
      throw Checks.bad("TOO_MANY");
    }
    Category category = new Category();
    fill(category, request);
    categories.saveAndFlush(category);
    return new CategoryView(category.getId(), category.getName(), category.isFixed(), 0, 0);
  }

  @Transactional
  public CategoryView update(long id, CategoryRequest request) {
    Category category = categories.findById(id).orElseThrow(NoSuchElementException::new);
    fill(category, request);
    categories.saveAndFlush(category);
    return new CategoryView(category.getId(), category.getName(), category.isFixed(), count(id),
        rules.findByCategoryId(id).size());
  }

  /** its rules go with it; its movements stay, without a category */
  @Transactional
  public void delete(long id) {
    Category category = categories.findById(id).orElseThrow(NoSuchElementException::new);
    em.createQuery("UPDATE BankTransaction t SET t.categoryId = NULL, t.categorySource = NULL WHERE t.categoryId = :id")
        .setParameter("id", id).executeUpdate();
    rules.deleteAll(rules.findByCategoryId(id));
    categories.delete(category);
  }

  @Transactional
  public RuleView createRule(RuleRequest request) {
    if (rules.count() >= MAX_RULES) {
      throw Checks.bad("TOO_MANY");
    }
    CategoryRule rule = new CategoryRule();
    fill(rule, request, null);
    return view(rules.saveAndFlush(rule));
  }

  @Transactional
  public RuleView updateRule(long id, RuleRequest request) {
    CategoryRule rule = rules.findById(id).orElseThrow(NoSuchElementException::new);
    fill(rule, request, id);
    return view(rules.saveAndFlush(rule));
  }

  /** the movements it gave a category keep it until "apply to all" */
  @Transactional
  public void deleteRule(long id) {
    rules.delete(rules.findById(id).orElseThrow(NoSuchElementException::new));
  }

  /**
   * The rules on every movement not chosen by hand: the category of the first rule that matches, none when no rule
   * matches. {@code dryRun}: only counts, nothing changes.
   */
  @Transactional
  public Applied apply(boolean dryRun) {
    List<CategoryRule> ordered = Rules.ordered(rules.findAll());
    List<BankTransaction> rows = em.createQuery("SELECT t FROM BankTransaction t WHERE t.categorySource IS NULL "
        + "OR t.categorySource <> :manual", BankTransaction.class)
        .setParameter("manual", BankTransaction.CategorySource.MANUAL).getResultList();
    int categorized = 0;
    int changed = 0;
    int cleared = 0;
    for (BankTransaction t : rows) {
      CategoryRule rule = Rules.first(ordered, t);
      Long now = rule == null ? null : rule.getCategoryId();
      if (Objects.equals(now, t.getCategoryId())) {
        if (now != null && t.getCategorySource() == null && !dryRun) {
          t.setCategorySource(BankTransaction.CategorySource.RULE);
        }
        continue;
      }
      if (t.getCategoryId() == null) {
        categorized++;
      } else if (now == null) {
        cleared++;
      } else {
        changed++;
      }
      if (!dryRun) {
        t.setCategoryId(now);
        t.setCategorySource(now == null ? null : BankTransaction.CategorySource.RULE);
      }
    }
    long manual = em.createQuery("SELECT count(t) FROM BankTransaction t WHERE t.categorySource = :manual", Long.class)
        .setParameter("manual", BankTransaction.CategorySource.MANUAL).getSingleResult();
    return new Applied(dryRun, categorized, changed, cleared, categorized + changed + cleared, manual);
  }

  /**
   * The category of one movement, chosen by hand: no rule changes it any more. Without a category it is free again
   * (a rule may give it one with "apply to all"). With {@code rule}: a rule for the movements like it, in the same
   * category; the answer says what "apply to all" would change then.
   */
  @Transactional
  public Assigned assign(long movementId, AssignRequest request) {
    BankTransaction t = transactions.findById(movementId).orElseThrow(NoSuchElementException::new);
    Long categoryId = request == null ? null : request.categoryId();
    Category category = null;
    if (categoryId != null) {
      category = categories.findById(categoryId).orElseThrow(() -> Checks.bad("CATEGORY"));
    }
    RuleView created = null;
    if (request != null && request.rule() != null) {
      if (category == null) {
        throw Checks.bad("CATEGORY");
      }
      NewRule r = request.rule();
      String accountId = Boolean.TRUE.equals(r.onlyThisAccount()) ? AccountPages.idOf(t.getAccount()) : null;
      created = createRule(new RuleRequest(categoryId, r.pattern(), r.direction(), accountId));
    }
    t.setCategoryId(categoryId);
    t.setCategorySource(categoryId == null ? null : BankTransaction.CategorySource.MANUAL);
    transactions.saveAndFlush(t);
    return new Assigned(t.getId(), categoryId, category == null ? null : category.getName(),
        t.getCategorySource() == null ? null : t.getCategorySource().name(), created,
        created == null ? null : apply(true));
  }

  private long count(long categoryId) {
    return em.createQuery("SELECT count(t) FROM BankTransaction t WHERE t.categoryId = :id", Long.class)
        .setParameter("id", categoryId).getSingleResult();
  }

  private void fill(Category category, CategoryRequest request) {
    String name = request == null || request.name() == null ? "" : request.name().strip().replaceAll("\\s+", " ");
    if (name.isEmpty()) {
      throw Checks.bad("NAME_REQUIRED");
    }
    if (name.length() > MAX_NAME) {
      throw Checks.bad("NAME_TOO_LONG");
    }
    boolean taken = categories.findAll().stream().anyMatch(c -> !c.getId().equals(category.getId())
        && c.getName().equalsIgnoreCase(name));
    if (taken) {
      throw Checks.bad("NAME_TAKEN");
    }
    category.setName(name);
    category.setFixed(request.fixed() != null && request.fixed());
  }

  private void fill(CategoryRule rule, RuleRequest request, Long id) {
    if (request == null || request.categoryId() == null || !categories.existsById(request.categoryId())) {
      throw Checks.bad("CATEGORY");
    }
    String pattern = request.pattern() == null ? "" : request.pattern().strip().replaceAll("\\s+", " ");
    if (pattern.isEmpty()) {
      throw Checks.bad("PATTERN_REQUIRED");
    }
    if (pattern.length() < MIN_PATTERN) {
      throw Checks.bad("PATTERN_TOO_SHORT");
    }
    if (pattern.length() > MAX_PATTERN) {
      throw Checks.bad("PATTERN_TOO_LONG");
    }
    String direction = request.direction() == null || request.direction().isBlank() ? null
        : request.direction().strip().toUpperCase(Locale.ROOT);
    if (direction != null && !direction.equals(Rules.IN) && !direction.equals(Rules.OUT)) {
      throw Checks.bad("DIRECTION");
    }
    String account = null;
    if (request.accountId() != null && !request.accountId().isBlank()) {
      try {
        account = pages.nameOf(request.accountId().strip());
      } catch (ResponseStatusException e) {
        throw Checks.bad("ACCOUNT");
      }
    }
    String finalAccount = account;
    boolean exists = rules.findAll().stream().anyMatch(r -> !r.getId().equals(id)
        && Rules.normalize(r.getPattern()).equals(Rules.normalize(pattern))
        && Objects.equals(r.getDirection(), direction) && Objects.equals(r.getAccount(), finalAccount));
    if (exists) {
      throw Checks.bad("RULE_EXISTS");
    }
    rule.setCategoryId(request.categoryId());
    rule.setPattern(pattern);
    rule.setDirection(direction);
    rule.setAccount(account);
  }

  static RuleView view(CategoryRule r) {
    return new RuleView(r.getId(), r.getCategoryId(), r.getPattern(), r.getDirection(),
        r.getAccount() == null ? null : AccountPages.idOf(r.getAccount()), r.getAccount());
  }

  /** for the sample data: a category with its rules, without checks */
  @Transactional
  public long seed(String name, boolean fixed, String... patterns) {
    Category category = new Category();
    category.setName(name);
    category.setFixed(fixed);
    categories.saveAndFlush(category);
    List<CategoryRule> list = new ArrayList<>();
    for (String pattern : patterns) {
      CategoryRule rule = new CategoryRule();
      rule.setCategoryId(category.getId());
      rule.setPattern(pattern);
      list.add(rule);
    }
    rules.saveAllAndFlush(list);
    return category.getId();
  }
}
