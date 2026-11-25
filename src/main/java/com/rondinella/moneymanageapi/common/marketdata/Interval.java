package com.rondinella.moneymanageapi.common.marketdata;

import lombok.Getter;

@Getter
public enum Interval {
  DAILY("d"),
  WEEKLY("w"),
  MONTHLY("m");

  private final String tag;

  Interval(String tag) {
    this.tag = tag;
  }
}

