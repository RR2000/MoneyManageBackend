package com.rondinella.moneymanageapi.auth;

import com.rondinella.moneymanageapi.tenant.TenantContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/** The logged-in user, with the database schema that holds their data. */
public class AppUserDetails extends org.springframework.security.core.userdetails.User {

  private final String schema;
  /** a demo sandbox: what reaches outside (bank, mail, sign-on, market prices) is refused (DemoGuard) */
  private final boolean demo;

  public AppUserDetails(User user) {
    super(user.getUsername(), user.getPassword(), List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
    this.schema = TenantContext.schemaOf(user.getId());
    this.demo = user.isDemo();
  }

  public boolean isDemo() {
    return demo;
  }

  public String getSchema() {
    return schema;
  }
}
