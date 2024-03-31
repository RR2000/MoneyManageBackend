package com.rondinella.moneymanageapi.common;

import lombok.SneakyThrows;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Utils extends org.apache.commons.lang3.time.DateUtils {

  public static void fillGaps(Set<String> daysList, Map<String, BigDecimal> points) {
    LinkedHashSet<String> orderedDaysList = new LinkedHashSet<>();
    orderedDaysList.addAll(daysList);
    orderedDaysList.addAll(points.keySet());
    sortLinkedHashSet(orderedDaysList);
    BigDecimal lastDayValue = points.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
    points.put(orderedDaysList.stream().findFirst().orElseThrow(), lastDayValue);

    daysList.forEach(day -> points.putIfAbsent(day, null));

    for (String day : orderedDaysList) {
      BigDecimal value = points.get(day);
      if (value != null) {
        lastDayValue = value;
      } else if (lastDayValue != null) {
        points.put(day, lastDayValue);
      }
    }
  }

  public static void sortLinkedHashSet(LinkedHashSet<String> linkedHashSet) {
    List<String> sortedDates = linkedHashSet.stream()
        .sorted()
        .toList();

    linkedHashSet.clear();

    linkedHashSet.addAll(sortedDates);
  }

  public static LinkedHashSet<String> getAllDaysBetweenTimestamps(Timestamp startTimestamp, Timestamp endTimestamp) {
    LinkedHashSet<String> allDays = new LinkedHashSet<>();

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

  @SneakyThrows
  public static Timestamp stringToTimestamp(String string) {
    return Timestamp.from(Utils.parseDate(
        string,
        "yyyy-MM-dd"
    ).toInstant());
  }

  public static String convertTimestampToString(Timestamp timestamp) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    return sdf.format(timestamp);
  }

  public static String convertDateToString(java.util.Date date) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
    return sdf.format(date);
  }

  public static Timestamp todayAsTimestamp() {
    LocalDateTime today = LocalDateTime.now();
    return Timestamp.valueOf(today);
  }

  public static String calendarToString(Calendar calendar) {
    return convertDateToString(calendar.getTime());
  }
}
