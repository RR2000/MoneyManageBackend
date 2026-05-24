package com.rondinella.moneymanageapi.common.configurations;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.account-base")
public class AccountBaseProperties {

  private Map<String, BigDecimal> amounts = new HashMap<>();

  public Map<String, BigDecimal> getAmounts() {
    return amounts;
  }

  public void setAmounts(Map<String, BigDecimal> amounts) {
    this.amounts = amounts;
  }
}
