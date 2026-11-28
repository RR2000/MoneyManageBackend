package com.rondinella.moneymanageapi.investments;

import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** The investments of the user who is logged in: positions, returns, allocation (see {@link Portfolio}). */
@RestController
@RequestMapping(path = "/api/investments", produces = "application/json")
public class PortfolioController {

  public record TargetsRequest(List<Portfolio.Target> targets) {
  }

  private final Portfolio portfolio;

  public PortfolioController(Portfolio portfolio) {
    this.portfolio = portfolio;
  }

  @GetMapping
  public Portfolio.Answer get() {
    return portfolio.compute(LocalDate.now(AccountPages.ZONE));
  }

  @GetMapping("/targets")
  public Portfolio.Targets targets() {
    return portfolio.targets();
  }

  /** replaces every target: {"targets":[{"isin","percent"}]} */
  @PutMapping("/targets")
  public Portfolio.Targets setTargets(@RequestBody TargetsRequest request) {
    return portfolio.setTargets(request == null ? null : request.targets());
  }
}
