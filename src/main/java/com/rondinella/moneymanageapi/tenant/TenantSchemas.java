package com.rondinella.moneymanageapi.tenant;

import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

/**
 * Schema of each user, managed by Flyway (db/tenant). The shared schema (users) is migrated by Spring Boot
 * at startup (db/public); here: a new schema at registration, and the pending migrations of every existing
 * user at startup.
 */
@Service
public class TenantSchemas implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(TenantSchemas.class);

  private final DataSource dataSource;
  private final UserRepository userRepository;

  public TenantSchemas(DataSource dataSource, UserRepository userRepository) {
    this.dataSource = dataSource;
    this.userRepository = userRepository;
  }

  public void migrate(String schema) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .locations("classpath:db/tenant")
        .load()
        .migrate();
  }

  @Override
  public void run(ApplicationArguments args) {
    var users = userRepository.findAll();
    for (User user : users) {
      migrate(TenantContext.schemaOf(user.getId()));
    }
    log.info("User schemas up to date: {}", users.size());
  }
}
