package com.rondinella.moneymanageapi.banktransactions;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The money accounts of the user who is logged in: list with counts, delete. The name goes in the body, never in the
 * URL (it would end up in the access logs). Errors carry a code in {@code message}: ACCOUNT_NOT_FOUND,
 * CONFIRMATION_MISMATCH, ACCOUNT_LINKED.
 */
@RestController
@RequestMapping(path = "/api/money-accounts", produces = "application/json")
public class MoneyAccountsController {

  public record DeleteRequest(String name, String confirm) {
  }

  private final MoneyAccounts accounts;

  public MoneyAccountsController(MoneyAccounts accounts) {
    this.accounts = accounts;
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
