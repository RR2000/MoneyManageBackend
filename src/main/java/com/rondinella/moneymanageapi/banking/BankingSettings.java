package com.rondinella.moneymanageapi.banking;

import java.security.PrivateKey;
import java.util.Set;

/**
 * Open banking through Enable Banking, from the environment. Off (every endpoint 404) unless the application id, a
 * readable private key, the web URL and at least one enabled user are set; on, it works only for the users listed
 * in {@code app.banking.users} (the application is in restricted mode: only its owner's own accounts).
 *
 * @param apiUrl  Enable Banking's API (the tests point it to a local server)
 * @param webUrl  public URL of the web app: the bank sends the browser back to {@code <webUrl>/api/banking/callback}
 */
public record BankingSettings(String appId, PrivateKey privateKey, Set<Long> users, String apiUrl, String webUrl) {

  public static final String CALLBACK_PATH = "/api/banking/callback";

  public boolean enabled() {
    return appId != null && !appId.isBlank() && privateKey != null && !users.isEmpty()
        && webUrl != null && !webUrl.isBlank();
  }

  public boolean enabledFor(Long userId) {
    return enabled() && userId != null && users.contains(userId);
  }

  public String redirectUrl() {
    String web = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
    return web + CALLBACK_PATH;
  }

  @Override
  public String toString() {
    return "BankingSettings[enabled=" + enabled() + ", users=" + users.size() + "]";
  }
}
