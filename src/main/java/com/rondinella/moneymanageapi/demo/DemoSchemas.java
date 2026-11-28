package com.rondinella.moneymanageapi.demo;

import com.rondinella.moneymanageapi.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a user's schema belongs to a demo sandbox, also outside a request (the monthly job of the plans works with
 * TenantContext.runAs and no login): the prices of a sandbox never come from the market (StockPrices). An account
 * never changes from demo to not demo or back, so the answer is kept.
 */
@Component
public class DemoSchemas {

  private final JdbcTemplate jdbc;
  private final Map<String, Boolean> known = new ConcurrentHashMap<>();

  public DemoSchemas(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean isDemo(String schema) {
    if (schema == null || !schema.matches("u[0-9]{1,18}")) {
      return false;
    }
    Boolean cached = known.get(schema);
    if (cached != null) {
      return cached;
    }
    List<Boolean> rows = jdbc.queryForList("SELECT demo FROM " + TenantContext.SHARED + ".users WHERE id = ?",
        Boolean.class, Long.parseLong(schema.substring(1)));
    if (rows.isEmpty()) {
      return false; // not kept: the row may come later
    }
    boolean demo = Boolean.TRUE.equals(rows.getFirst());
    known.put(schema, demo);
    return demo;
  }

  void forget(String schema) {
    known.remove(schema);
  }
}
