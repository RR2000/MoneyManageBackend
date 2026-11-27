package com.rondinella.moneymanageapi.banktransactions;

import com.rondinella.moneymanageapi.banking.BankLinkAccount;
import com.rondinella.moneymanageapi.banking.BankLinkAccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The money accounts of the current user (the account names of bank_transaction), for every user: how many rows each
 * has and where they come from, and deleting one with all its rows and daily balances. An account where the movements
 * of a linked bank go cannot be deleted: first it is unlinked (or another money account is picked for the bank).
 * Broker operations, instruments, prices and real estate are not touched.
 */
@Service
public class MoneyAccounts {

  /** One money account: rows by source, first and last day, and whether a bank account imports into it. */
  public record Summary(String name, long rows, long fileRows, long manualRows, long bankRows, LocalDate firstDate,
                       LocalDate lastDate, boolean linked) {
  }

  public record Deleted(String name, long rows) {
  }

  private final BankTransactionRepository transactions;
  private final DailyBalanceRepository dailyBalances;
  private final BankLinkAccountRepository bankAccounts;
  private final TransactionTemplate transaction;

  public MoneyAccounts(BankTransactionRepository transactions, DailyBalanceRepository dailyBalances,
                       BankLinkAccountRepository bankAccounts, PlatformTransactionManager transactionManager) {
    this.transactions = transactions;
    this.dailyBalances = dailyBalances;
    this.bankAccounts = bankAccounts;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public List<Summary> list() {
    Set<String> linked = linked();
    Map<String, long[]> counts = new HashMap<>();
    Map<String, Timestamp[]> days = new HashMap<>();
    for (Object[] row : transactions.countByAccountAndSource()) {
      String name = (String) row[0];
      long[] c = counts.computeIfAbsent(name, k -> new long[3]);
      long n = ((Number) row[2]).longValue();
      switch ((BankTransaction.Source) row[1]) {
        case FILE -> c[0] += n;
        case MANUAL -> c[1] += n;
        case BANK -> c[2] += n;
      }
      Timestamp[] d = days.computeIfAbsent(name, k -> new Timestamp[2]);
      Timestamp first = (Timestamp) row[3];
      Timestamp last = (Timestamp) row[4];
      if (first != null && (d[0] == null || first.before(d[0]))) {
        d[0] = first;
      }
      if (last != null && (d[1] == null || last.after(d[1]))) {
        d[1] = last;
      }
    }
    List<Summary> result = new ArrayList<>();
    counts.forEach((name, c) -> result.add(new Summary(name, c[0] + c[1] + c[2], c[0], c[1], c[2],
        day(days.get(name)[0]), day(days.get(name)[1]), linked.contains(name))));
    result.sort(Comparator.comparing(Summary::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Summary::name));
    return result;
  }

  /**
   * Deletes the account: every row and every daily balance, in one transaction. {@code confirm} is the name typed by
   * the user and must be the same, character by character.
   */
  public Deleted delete(String name, String confirm) {
    if (name == null || name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND");
    }
    if (!name.equals(confirm)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CONFIRMATION_MISMATCH");
    }
    if (linked().contains(name)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_LINKED");
    }
    return transaction.execute(status -> {
      int rows = transactions.deleteAllOfAccount(name);
      if (rows == 0) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND");
      }
      dailyBalances.deleteAllOfAccount(name);
      return new Deleted(name, rows);
    });
  }

  private Set<String> linked() {
    return bankAccounts.findAll().stream().map(BankLinkAccount::getMoneyAccount).filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  private static LocalDate day(Timestamp t) {
    return t == null ? null : t.toLocalDateTime().toLocalDate();
  }
}
