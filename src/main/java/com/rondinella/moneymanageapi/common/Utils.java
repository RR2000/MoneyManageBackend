package com.rondinella.moneymanageapi.common;

import lombok.SneakyThrows;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.*;

public class Utils extends org.apache.commons.lang3.time.DateUtils {

  /** Longest period a graph may cover: one label per day, so the answer stays small. */
  public static final int MAX_YEARS = 60;

  /** 400 unless from <= to and the period is at most {@link #MAX_YEARS} years. */
  public static void requireSaneRange(Timestamp from, Timestamp to) {
    if (from == null || to == null || from.after(to)
        || to.toLocalDateTime().isAfter(from.toLocalDateTime().plusYears(MAX_YEARS))) {
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
          "Period: from must come before to, at most " + MAX_YEARS + " years");
    }
  }

  @SneakyThrows
  public static Timestamp stringToTimestamp(String string) {
    return Timestamp.from(Utils.parseDate(
        string,
        "yyyy-MM-dd",
        "yyyy-MM-dd hh:mm:ss.SSS"
    ).toInstant());
  }

  public static java.sql.Date convertJavaToSqlDate(Date javaDate) {
    if(javaDate == null)
      return null;
    return new java.sql.Date(javaDate.getTime());
  }

  public static String convertDateToString(java.util.Date date) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    return sdf.format(date);
  }
}
