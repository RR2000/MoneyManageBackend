package com.rondinella.moneymanageapi;

import org.springframework.boot.SpringApplication;

/**
 * Runs the backend on an embedded PostgreSQL (empty at every start): mvn spring-boot:test-run
 * Optional: -Dmoney.port=8080 for the HTTP port.
 */
public class LocalMoneyManageApplication {

  public static void main(String[] args) {
    System.setProperty("spring.datasource.url", EmbeddedDb.jdbcUrl(0));
    System.setProperty("spring.datasource.username", "postgres");
    System.setProperty("spring.datasource.password", "postgres");
    System.setProperty("server.port", System.getProperty("money.port", "8080"));
    System.setProperty("springdoc.api-docs.enabled", "true");
    System.setProperty("springdoc.swagger-ui.enabled", "true");
    SpringApplication.run(MoneyManageApiApplication.class, args);
  }
}
