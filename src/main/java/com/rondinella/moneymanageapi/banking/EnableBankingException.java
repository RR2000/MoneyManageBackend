package com.rondinella.moneymanageapi.banking;

import java.io.IOException;

/**
 * A refusal or a failure of Enable Banking. Holds only the error code (Enable Banking's own, such as EXPIRED_SESSION,
 * or HTTP_status / UNREACHABLE) and the endpoint pattern: never a body, an id or a code of the user.
 */
public class EnableBankingException extends IOException {

  public static final String UNREACHABLE = "UNREACHABLE";

  private final int status;
  private final String code;

  public EnableBankingException(int status, String code, String endpoint) {
    super("Enable Banking " + code + " on " + endpoint);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  /** The consent is over: a new link is needed. */
  public boolean sessionOver() {
    return "EXPIRED_SESSION".equals(code) || "REVOKED_SESSION".equals(code) || "CLOSED_SESSION".equals(code)
        || "SESSION_DOES_NOT_EXIST".equals(code);
  }

  public boolean rateLimited() {
    return "ASPSP_RATE_LIMIT_EXCEEDED".equals(code);
  }
}
