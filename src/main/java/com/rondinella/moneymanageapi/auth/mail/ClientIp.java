package com.rondinella.moneymanageapi.auth.mail;

import jakarta.servlet.http.HttpServletRequest;

/** The client's address for the limits. */
public final class ClientIp {

  private ClientIp() {
  }

  /**
   * X-Real-IP is set by the TLS reverse proxy in front (swag, the address it sees on the connection) and passed on by
   * the web app's nginx: unlike the first X-Forwarded-For entry, the client cannot choose it. Without it (local
   * runs, tests) the address of the connection.
   */
  public static String of(HttpServletRequest request) {
    String real = request.getHeader("X-Real-IP");
    return real != null && !real.isBlank() ? real.trim() : request.getRemoteAddr();
  }
}
