package com.rondinella.moneymanageapi.banking;

import com.rondinella.moneymanageapi.banking.EnableBanking.Tx;
import com.rondinella.moneymanageapi.banktransactions.BankTransaction;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Movements from the bank into a money account (in the current user's schema), never twice.
 *
 * <ul>
 *   <li>Only booked movements (BOOK), from {@code syncFrom} on. The sign comes from credit_debit_indicator (DBIT is
 *   money going out), the currency is the movement's own, never converted.</li>
 *   <li>Each movement has a bank id, unique per account: entry_reference, or when the bank gives none a hash of booking
 *   date, amount, currency, text, counterparty and the ordinal among equal movements of that day. A movement whose id
 *   is already in the account is skipped: two syncs of the same days add nothing.</li>
 *   <li>A new movement is first matched to a row without a bank id (from a file or typed in) of the same amount and
 *   currency: same day first, otherwise one day before or after. As many rows as movements: they get the bank id
 *   instead of a new row. More rows than movements: ambiguous, counted, nothing added (the movement is already among
 *   them, which one is not known). Fewer: the rows are matched and the rest added.</li>
 *   <li>With {@code bankWins} (a whole history asked by the user, or "replace with the bank's data") the bank is the
 *   truth: more rows than movements is not ambiguous any more, the first rows (by id) get the movements and the rest
 *   stay without a bank id, so every movement of the bank ends up in the account exactly once.</li>
 * </ul>
 */
@Component
public class BankImport {

  public record Result(int imported, int matched, int ambiguous, LocalDate lastBookingDate) {
  }

  private record Incoming(Tx tx, String ref, BigDecimal amount, String currency, LocalDate day) {
  }

  private final BankTransactionRepository transactions;

  public BankImport(BankTransactionRepository transactions) {
    this.transactions = transactions;
  }

  public Result importInto(String moneyAccount, LocalDate syncFrom, List<Tx> txs) {
    return importInto(moneyAccount, syncFrom, txs, false);
  }

  public Result importInto(String moneyAccount, LocalDate syncFrom, List<Tx> txs, boolean bankWins) {
    List<Incoming> booked = booked(txs, syncFrom);
    LocalDate last = booked.stream().map(Incoming::day).max(Comparator.naturalOrder()).orElse(null);
    Set<String> known = new HashSet<>(transactions.findExternalRefs(moneyAccount));
    List<Incoming> fresh = new ArrayList<>();
    for (Incoming in : booked) {
      if (known.add(in.ref())) {
        fresh.add(in);
      }
    }
    if (fresh.isEmpty()) {
      return new Result(0, 0, 0, last);
    }

    LocalDate from = fresh.stream().map(Incoming::day).min(Comparator.naturalOrder()).orElseThrow().minusDays(1);
    LocalDate to = fresh.stream().map(Incoming::day).max(Comparator.naturalOrder()).orElseThrow().plusDays(1);
    List<BankTransaction> candidates = new ArrayList<>(transactions.findByAccountAndExternalRefIsNullAndDatetimeBetweenOrderByIdAsc(
        moneyAccount, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(to.plusDays(1).atStartOfDay().minusNanos(1000))));

    List<BankTransaction> toSave = new ArrayList<>();
    int ambiguous = 0;
    int matched = 0;
    // pass 1: same day; pass 2: what is left, one day before or after
    List<Incoming> left = new ArrayList<>();
    for (List<Incoming> group : groups(fresh).values()) {
      Incoming first = group.getFirst();
      List<BankTransaction> pool = pool(candidates, first, 0);
      if (pool.size() > group.size() && !bankWins) {
        ambiguous += group.size();
        candidates.removeAll(pool);
        continue;
      }
      for (int i = 0; i < group.size(); i++) {
        if (i < pool.size()) {
          toSave.add(match(pool.get(i), group.get(i)));
          candidates.remove(pool.get(i));
          matched++;
        } else {
          left.add(group.get(i));
        }
      }
    }
    int imported = 0;
    for (List<Incoming> group : groups(left).values()) {
      Incoming first = group.getFirst();
      List<BankTransaction> pool = pool(candidates, first, 1);
      if (pool.size() > group.size() && !bankWins) {
        ambiguous += group.size();
        continue;
      }
      for (int i = 0; i < group.size(); i++) {
        if (i < pool.size()) {
          toSave.add(match(pool.get(i), group.get(i)));
          candidates.remove(pool.get(i));
          matched++;
        } else {
          toSave.add(row(moneyAccount, group.get(i)));
          imported++;
        }
      }
    }
    transactions.saveAllAndFlush(toSave);
    return new Result(imported, matched, ambiguous, last);
  }

  /** The oldest booked day among the movements (as {@link #importInto} reads them), null if none. */
  public static LocalDate oldestBooked(List<Tx> txs) {
    return booked(txs, null).stream().map(Incoming::day).min(Comparator.naturalOrder()).orElse(null);
  }

  private static List<Incoming> booked(List<Tx> txs, LocalDate syncFrom) {
    List<Incoming> result = new ArrayList<>();
    Map<String, Integer> ordinals = new HashMap<>();
    for (Tx tx : txs) {
      if (tx.bookingDate() == null || tx.amount() == null || tx.currency() == null
          || (tx.status() != null && !"BOOK".equals(tx.status()))) {
        continue;
      }
      BigDecimal amount = tx.amount().abs();
      if ("DBIT".equals(tx.indicator())) {
        amount = amount.negate();
      } else if (!"CRDT".equals(tx.indicator())) {
        amount = tx.amount();
      }
      String currency = tx.currency().toUpperCase(Locale.ROOT);
      String ref;
      if (tx.entryReference() != null) {
        ref = tx.entryReference().length() <= 200 ? tx.entryReference() : "h:" + sha256(tx.entryReference());
      } else {
        String content = String.join("|", tx.bookingDate().toString(), amount.stripTrailingZeros().toPlainString(),
            currency, nn(tx.text()), nn(tx.counterparty()));
        int ordinal = ordinals.merge(content, 1, Integer::sum) - 1;
        ref = "h:" + sha256(content + "|" + ordinal).substring(0, 40);
      }
      if (syncFrom == null || !tx.bookingDate().isBefore(syncFrom)) {
        result.add(new Incoming(tx, ref, amount, currency, tx.bookingDate()));
      }
    }
    return result;
  }

  /** Movements of the same day, amount and currency: interchangeable for the matching. */
  private static Map<List<Object>, List<Incoming>> groups(List<Incoming> incoming) {
    Map<List<Object>, List<Incoming>> groups = new LinkedHashMap<>();
    incoming.stream().sorted(Comparator.comparing(Incoming::day)).forEach(in -> groups
        .computeIfAbsent(List.of(in.day(), in.amount().stripTrailingZeros(), in.currency()), k -> new ArrayList<>())
        .add(in));
    return groups;
  }

  /** Rows without a bank id of the same amount and currency, on the same day (days = 0) or up to a day away. */
  private static List<BankTransaction> pool(List<BankTransaction> candidates, Incoming in, int days) {
    List<BankTransaction> pool = new ArrayList<>();
    for (BankTransaction row : candidates) {
      LocalDate day = row.getDatetime().toLocalDateTime().toLocalDate();
      long distance = Math.abs(day.toEpochDay() - in.day().toEpochDay());
      if (distance <= days && row.getAmount() != null && row.getAmount().compareTo(in.amount()) == 0
          && row.getCurrency() != null && row.getCurrency().equalsIgnoreCase(in.currency())) {
        pool.add(row);
      }
    }
    return pool;
  }

  private static BankTransaction match(BankTransaction row, Incoming in) {
    row.setExternalRef(in.ref());
    return row;
  }

  private static BankTransaction row(String moneyAccount, Incoming in) {
    BankTransaction row = new BankTransaction();
    row.setAccount(moneyAccount);
    row.setDatetime(Timestamp.valueOf(in.day().atStartOfDay()));
    String description = in.tx().text() != null && !in.tx().text().isBlank() ? in.tx().text()
        : nn(in.tx().counterparty());
    row.setDescription(description.length() > 500 ? description.substring(0, 500) : description);
    row.setAmount(in.amount());
    row.setFee(BigDecimal.ZERO);
    row.setCurrency(in.currency());
    row.setSource(BankTransaction.Source.BANK);
    row.setExternalRef(in.ref());
    return row;
  }

  private static String nn(String s) {
    return s == null ? "" : s.trim();
  }

  static String sha256(String s) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
