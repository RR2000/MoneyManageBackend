package com.rondinella.moneymanageapi.tenant;

import com.rondinella.moneymanageapi.auth.AppUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.function.Supplier;

/**
 * Every user has their own database schema: the tables of the data (transactions, real estate, ...) are
 * the same, the rows are never shared. The schema of the request comes from the logged-in user, or from
 * {@link #runAs} for work done on behalf of a user outside a request (registration, sample data).
 * The users table lives in the shared {@link #SHARED} schema.
 */
public final class TenantContext {

  public static final String SHARED = "public";

  private static final ThreadLocal<String> OVERRIDE = new ThreadLocal<>();

  private TenantContext() {
  }

  public static String schemaOf(Long userId) {
    return "u" + userId;
  }

  public static String current() {
    String forced = OVERRIDE.get();
    if (forced != null) {
      return forced;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AppUserDetails user) {
      return user.getSchema();
    }
    return SHARED;
  }

  public static <T> T runAs(String schema, Supplier<T> work) {
    String previous = OVERRIDE.get();
    OVERRIDE.set(schema);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        OVERRIDE.remove();
      } else {
        OVERRIDE.set(previous);
      }
    }
  }
}
