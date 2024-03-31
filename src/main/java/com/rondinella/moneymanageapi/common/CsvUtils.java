package com.rondinella.moneymanageapi.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CsvUtils {
  public static List<Map<String, String>> csvToMap(String csvData) {
    List<Map<String, String>> result = new ArrayList<>();
    BufferedReader reader = new BufferedReader(new StringReader(csvData));
    String line;
    try {
      String[] headers = reader.readLine().split(",");
      while ((line = reader.readLine()) != null) {
        String[] data = line.split(",", -1);
        if (data.length != headers.length)
          throw new RuntimeException("data != headers length");

        Map<String, String> rowData = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
          rowData.put(headers[i], data[i]);
        }

        result.add(rowData);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return result;
  }
}
