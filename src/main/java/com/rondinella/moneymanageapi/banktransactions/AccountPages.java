package com.rondinella.moneymanageapi.banktransactions;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * One money account, for its page: balance, this month's money in and out, balance history, and its movements newest
 * first with search, filter and paging. Accounts are addressed by an id derived from the name (a hash), so the name
 * never appears in a URL or an access log.
 */
@Service
public class AccountPages {

  /** what the movements list shows */
  public enum Kind {
    ALL, IN, OUT
  }

  public record Detail(String id, String name, String currency, BigDecimal balance, LocalDate balanceDate,
                       BigDecimal monthIn, BigDecimal monthOut, long rows, LocalDate firstDate, LocalDate lastDate,
                       boolean linked) {
  }

  public record Movement(long id, Timestamp datetime, String description, BigDecimal amount, String currency,
                         BankTransaction.Source source, BigDecimal balance) {
  }

  public record Page(List<Movement> items, boolean hasMore) {
  }

  public record Point(LocalDate date, BigDecimal balance) {
  }

  static final int MAX_LIMIT = 200;
  static final ZoneId ZONE = ZoneId.of("Europe/Rome");

  private final EntityManager em;
  private final BankTransactionRepository transactions;
  private final DailyBalanceRepository dailyBalances;
  private final MoneyAccounts accounts;

  public AccountPages(EntityManager em, BankTransactionRepository transactions, DailyBalanceRepository dailyBalances,
                      MoneyAccounts accounts) {
    this.em = em;
    this.transactions = transactions;
    this.dailyBalances = dailyBalances;
    this.accounts = accounts;
  }

  /** The id of an account: 12 hex characters of the SHA-256 of its name, the same for the same name. */
  public static String idOf(String name) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The name of the account with this id, or 404. */
  public String nameOf(String id) {
    if (id == null || !id.matches("[0-9a-f]{12}")) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND");
    }
    return transactions.findDistinctAccounts().stream().filter(name -> idOf(name).equals(id)).findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND"));
  }

  @Transactional(readOnly = true)
  public Detail detail(String id, LocalDate today) {
    String name = nameOf(id);
    MoneyAccounts.Summary summary = accounts.list().stream().filter(s -> s.name().equals(name)).findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND"));
    DailyBalance lastDay = dailyBalances.findFirstByAccountOrderByDateDesc(name);
    LocalDate balanceDate = lastDay != null ? lastDay.getDate() : summary.lastDate();

    YearMonth month = YearMonth.from(today);
    Timestamp from = Timestamp.valueOf(month.atDay(1).atStartOfDay());
    Timestamp to = Timestamp.valueOf(month.plusMonths(1).atDay(1).atStartOfDay());
    Object[] sums = em.createQuery("SELECT coalesce(sum(CASE WHEN t.amount > 0 THEN t.amount ELSE 0 END), 0), "
            + "coalesce(sum(CASE WHEN t.amount < 0 THEN t.amount ELSE 0 END), 0) FROM BankTransaction t "
            + "WHERE t.account = :a AND t.datetime >= :from AND t.datetime < :to", Object[].class)
        .setParameter("a", name).setParameter("from", from).setParameter("to", to).getSingleResult();
    return new Detail(id, name, summary.currency(), summary.balance(), balanceDate, (BigDecimal) sums[0], (BigDecimal) sums[1],
        summary.rows(), summary.firstDate(), summary.lastDate(), summary.linked());
  }

  /** Balance at the end of every day with a balance, oldest first, from {@code from} (null: all). */
  @Transactional(readOnly = true)
  public List<Point> balances(String id, LocalDate from) {
    String name = nameOf(id);
    List<DailyBalance> rows = from == null ? dailyBalances.findByAccountOrderByDate(name)
        : dailyBalances.findByAccountAndDateBetweenOrderByDate(name, from, LocalDate.now(ZONE).plusDays(1));
    return rows.stream().map(b -> new Point(b.getDate(), b.getBalance())).toList();
  }

  /**
   * Movements newest first. {@code q}: words of the description (any case) or an amount ("64.2", "64,20", "-64.2":
   * the sign is ignored); {@code kind}: money in, out or all; {@code offset}/{@code limit} for "load more".
   */
  @Transactional(readOnly = true)
  public Page movements(String id, String q, Kind kind, int offset, int limit) {
    String name = nameOf(id);
    int size = Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_LIMIT));
    StringBuilder jpql = new StringBuilder("SELECT t FROM BankTransaction t WHERE t.account = :a");
    if (kind == Kind.IN) {
      jpql.append(" AND t.amount > 0");
    } else if (kind == Kind.OUT) {
      jpql.append(" AND t.amount < 0");
    }
    String text = q == null ? "" : q.strip();
    BigDecimal number = parseAmount(text);
    if (!text.isEmpty()) {
      jpql.append(number != null ? " AND (lower(t.description) LIKE :q ESCAPE '\\' OR abs(t.amount) = :n)"
          : " AND lower(t.description) LIKE :q ESCAPE '\\'");
    }
    jpql.append(" ORDER BY t.datetime DESC, t.id DESC");
    TypedQuery<BankTransaction> query = em.createQuery(jpql.toString(), BankTransaction.class).setParameter("a", name);
    if (!text.isEmpty()) {
      query.setParameter("q", "%" + likeEscape(text.toLowerCase(Locale.ROOT)) + "%");
      if (number != null) {
        query.setParameter("n", number.abs());
      }
    }
    List<BankTransaction> rows = query.setFirstResult(Math.max(0, offset)).setMaxResults(size + 1).getResultList();
    List<Movement> items = new ArrayList<>();
    for (int i = 0; i < Math.min(size, rows.size()); i++) {
      BankTransaction t = rows.get(i);
      items.add(new Movement(t.getId(), t.getDatetime(), t.getDescription(), t.getAmount(), t.getCurrency(), t.getSource(),
          t.getCumulativeAmount()));
    }
    return new Page(items, rows.size() > size);
  }

  /** an amount typed in the search box, with a decimal comma or dot; null if it is not a number */
  static BigDecimal parseAmount(String text) {
    String s = text.replace("−", "-").replace("€", "").strip();
    if (!s.matches("[+-]?\\d{1,12}([.,]\\d{1,4})?")) {
      return null;
    }
    return new BigDecimal(s.replace(',', '.'));
  }

  /** for the tests */
  public static BigDecimal parseAmountForTest(String text) {
    return parseAmount(text);
  }

  private static String likeEscape(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
