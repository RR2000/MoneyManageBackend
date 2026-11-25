package com.rondinella.moneymanageapi.auth;

import com.rondinella.moneymanageapi.tenant.TenantContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/** The logged-in user, with the database schema that holds their data. */
public class AppUserDetails extends org.springframework.security.core.userdetails.User {

  private final String schema;

  public AppUserDetails(User user) {
    super(user.getUsername(), user.getPassword(), List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
    this.schema = TenantContext.schemaOf(user.getId());
  }

  public String getSchema() {
    return schema;
  }
}
