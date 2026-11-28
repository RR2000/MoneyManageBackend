package com.rondinella.moneymanageapi.categories;

import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The fixed costs of the user who is logged in, over the last {@code months} complete months (1 to 36), see {@link FixedCosts}. */
@RestController
@RequestMapping(path = "/api/fixed-costs", produces = "application/json")
public class FixedCostsController {

  private final FixedCosts fixedCosts;

  public FixedCostsController(FixedCosts fixedCosts) {
    this.fixedCosts = fixedCosts;
  }

  @GetMapping
  public FixedCosts.Answer get(@RequestParam(defaultValue = "12") int months) {
    return fixedCosts.of(LocalDate.now(AccountPages.ZONE), months);
  }
}
