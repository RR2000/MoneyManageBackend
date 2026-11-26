package com.rondinella.moneymanageapi.auth.mail;

/**
 * Outgoing mail (password reset, email confirmation) through an SMTP relay with STARTTLS. Everything comes from the
 * environment; with any of host, username, password, sender or web URL empty the feature is off: the endpoints answer
 * 404 and the web app does not show it.
 *
 * @param webUrl where the web app lives (links in the emails are built from here, never from the request's Host)
 */
public record MailSettings(String host, int port, String username, String password, String from, String fromName,
                           String webUrl) {

  public boolean enabled() {
    return !blank(host) && !blank(username) && !blank(password) && !blank(from) && !blank(webUrl);
  }

  /** The web URL without a trailing slash. */
  public String web() {
    return webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  @Override
  public String toString() {
    return "MailSettings[host=" + host + ", port=" + port + ", enabled=" + enabled() + "]";
  }
}
