package com.rondinella.moneymanageapi.tenant;

import org.flywaydb.core.Flyway;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The migrations of a user's schema (db/tenant), and the guarantee that a schema is there before it is used. A user
 * row without its schema (a registration whose migration failed half way, a schema dropped by hand) used to make
 * every page of that user fail with "relation does not exist"; now the first connection to the schema in this run of
 * the server creates it or brings it up to date. Only on the first use: then it is remembered.
 */
@Component
public class TenantMigrations {

  private final DataSource dataSource;
  private final Set<String> ready = ConcurrentHashMap.newKeySet();

  public TenantMigrations(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** creates the schema if missing and applies the pending migrations (Flyway locks the schema while it works) */
  public void migrate(String schema) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .locations("classpath:db/tenant")
        .load()
        .migrate();
    ready.add(schema);
  }

  /** {@link #migrate} once per run of the server: the next calls cost a lookup */
  public void ensure(String schema) {
    if (ready.contains(schema)) {
      return;
    }
    synchronized (this) {
      if (!ready.contains(schema)) {
        migrate(schema);
      }
    }
  }

  /** after the schema was dropped (a demo sandbox removed): a later use would create it again */
  public void forget(String schema) {
    ready.remove(schema);
  }
}
