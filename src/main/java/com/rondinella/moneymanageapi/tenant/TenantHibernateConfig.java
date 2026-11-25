package com.rondinella.moneymanageapi.tenant;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * Hibernate multi-tenancy, one schema per user: each session gets a connection switched to the schema
 * of {@link TenantContext#current()}.
 */
@Component
public class TenantHibernateConfig implements HibernatePropertiesCustomizer,
    MultiTenantConnectionProvider<String>, CurrentTenantIdentifierResolver<String> {

  private final transient DataSource dataSource;

  public TenantHibernateConfig(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Override
  public void customize(Map<String, Object> properties) {
    properties.put(AvailableSettings.MULTI_TENANT_CONNECTION_PROVIDER, this);
    properties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
  }

  // CurrentTenantIdentifierResolver

  @Override
  public String resolveCurrentTenantIdentifier() {
    return TenantContext.current();
  }

  @Override
  public boolean validateExistingCurrentSessions() {
    return true;
  }

  // MultiTenantConnectionProvider

  @Override
  public Connection getAnyConnection() throws SQLException {
    return dataSource.getConnection();
  }

  @Override
  public void releaseAnyConnection(Connection connection) throws SQLException {
    connection.close();
  }

  @Override
  public Connection getConnection(String schema) throws SQLException {
    Connection connection = getAnyConnection();
    try {
      connection.setSchema(schema);
    } catch (SQLException e) {
      connection.close();
      throw e;
    }
    return connection;
  }

  @Override
  public void releaseConnection(String schema, Connection connection) throws SQLException {
    try {
      connection.setSchema(TenantContext.SHARED);
    } finally {
      connection.close();
    }
  }

  @Override
  public boolean supportsAggressiveRelease() {
    return false;
  }

  @Override
  public boolean isUnwrappableAs(Class<?> unwrapType) {
    return unwrapType.isInstance(this);
  }

  @Override
  public <T> T unwrap(Class<T> unwrapType) {
    return unwrapType.cast(this);
  }
}
