package com.rondinella.moneymanageapi.banktransactions;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The money accounts of the user who is logged in: list with counts, delete, and the page of one account. The name
 * goes in the body, never in the URL (it would end up in the access logs): one account is addressed by its id (a hash
 * of the name), and the search of its movements travels in the body. Errors carry a code in {@code message}:
 * ACCOUNT_NOT_FOUND, CONFIRMATION_MISMATCH, ACCOUNT_LINKED.
 */
@RestController
@RequestMapping(path = "/api/money-accounts", produces = "application/json")
public class MoneyAccountsController {

  public record DeleteRequest(String name, String confirm) {
  }

  /** search of the movements: words or an amount, in or out, only the ones without a category, and the page */
  public record MovementsRequest(String q, AccountPages.Kind kind, Boolean uncategorized, Integer offset, Integer limit) {
  }

  private final MoneyAccounts accounts;
  private final AccountPages pages;

  public MoneyAccountsController(MoneyAccounts accounts, AccountPages pages) {
    this.accounts = accounts;
    this.pages = pages;
  }

  /** the newest movements of every account (at most 50), newest first */
  @GetMapping("/movements/latest")
  public List<AccountPages.Latest> latest(@RequestParam(defaultValue = "8") int limit) {
    return pages.latest(limit);
  }

  @GetMapping("/{id}")
  public AccountPages.Detail detail(@PathVariable String id) {
    return pages.detail(id, LocalDate.now(AccountPages.ZONE));
  }

  /** balance at the end of each day, from {@code from} (ISO date) or from the start */
  @GetMapping("/{id}/balances")
  public List<AccountPages.Point> balances(@PathVariable String id,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
    return pages.balances(id, from);
  }

  @PostMapping("/{id}/movements")
  public AccountPages.Page movements(@PathVariable String id, @RequestBody(required = false) MovementsRequest body) {
    return search(id, body);
  }

  /** the movements of every account, newest first, with the same search (e.g. the ones without a category) */
  @PostMapping("/movements")
  public AccountPages.Page allMovements(@RequestBody(required = false) MovementsRequest body) {
    return search(null, body);
  }

  private AccountPages.Page search(String id, MovementsRequest body) {
    MovementsRequest r = body == null ? new MovementsRequest(null, null, null, null, null) : body;
    return pages.movements(id, r.q(), r.kind() == null ? AccountPages.Kind.ALL : r.kind(),
        Boolean.TRUE.equals(r.uncategorized()), r.offset() == null ? 0 : r.offset(), r.limit() == null ? 50 : r.limit());
  }

  @GetMapping
  public List<MoneyAccounts.Summary> list() {
    return accounts.list();
  }

  @PostMapping("/delete")
  public MoneyAccounts.Deleted delete(@RequestBody DeleteRequest body) {
    return accounts.delete(body == null ? null : body.name(), body == null ? null : body.confirm());
  }
}
