package com.rondinella.moneymanageapi.networth;

import com.rondinella.moneymanageapi.banktransactions.AccountPages;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** The net worth of the user who is logged in, today and over time (see {@link NetWorth}). */
@RestController
@RequestMapping(path = "/api/net-worth", produces = "application/json")
public class NetWorthController {

  private final NetWorth netWorth;

  public NetWorthController(NetWorth netWorth) {
    this.netWorth = netWorth;
  }

  /** from {@code from} (ISO date) or from the first day with data */
  @GetMapping
  public NetWorth.Answer get(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
    return netWorth.compute(from, LocalDate.now(AccountPages.ZONE));
  }
}
