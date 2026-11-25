package com.rondinella.moneymanageapi;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;

/** One real PostgreSQL per JVM (binaries from Maven, no Docker), for the tests and for local runs. */
final class EmbeddedDb {

  private static EmbeddedPostgres postgres;

  private EmbeddedDb() {
  }

  static synchronized String jdbcUrl(int port) {
    if (postgres == null) {
      try {
        EmbeddedPostgres.Builder builder = EmbeddedPostgres.builder();
        if (port > 0) builder.setPort(port);
        postgres = builder.start();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return postgres.getJdbcUrl("postgres", "postgres");
  }
}
