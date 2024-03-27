package com.rondinella.moneymanageapi.common;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class DateUtils extends org.apache.commons.lang3.time.DateUtils{
  public static List<String> getAllDaysBetweenTimestamps(Timestamp startTimestamp, Timestamp endTimestamp) {
    List<String> allDays = new ArrayList<>();

    LocalDate startDate = startTimestamp.toLocalDateTime().toLocalDate(); // Convert seconds to days
    LocalDate endDate = endTimestamp.toLocalDateTime().toLocalDate(); // Convert seconds to days

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    LocalDate currentDate = startDate;
    while (!currentDate.isAfter(endDate)) {
      allDays.add(currentDate.format(formatter));
      currentDate = currentDate.plusDays(1);
    }

    return allDays;
  }

  public static String convertTimestampToString(Timestamp timestamp) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    return sdf.format(timestamp);
  }
}
