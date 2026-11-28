package com.rondinella.moneymanageapi.auth.oidc;

import java.util.Locale;

/**
 * Which logins the site offers (AUTH_MODE), so the move to the single sign-on can be undone by changing one value:
 * <ul>
 *   <li>legacy: username and password, "Accedi con GitHub"; the site's OpenID provider is off even if configured</li>
 *   <li>both: the above plus "Entra con raoulrondinella.dev"</li>
 *   <li>oidc: only "Entra con raoulrondinella.dev"; login, sign-up and password reset by email answer 404</li>
 * </ul>
 * Accounts and their data never change with the mode: going back to legacy or both gives the password back to the
 * accounts that had one (accounts created by the OpenID provider have none: "forgot password" sets it, by email).
 */
public enum AuthMode {
  LEGACY, BOTH, OIDC;

  public static AuthMode parse(String value) {
    String v = value == null ? "" : value.trim();
    if (v.isEmpty()) {
      return LEGACY;
    }
    try {
      return valueOf(v.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("AUTH_MODE must be legacy, both or oidc");
    }
  }
}
