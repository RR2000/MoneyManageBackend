package com.rondinella.moneymanageapi.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class GraphPointsDto implements Serializable {
  LinkedHashSet<String> xLabels = new LinkedHashSet<>();
  List<String> lineNames = new ArrayList<>();
  Map<String, Map<String, BigDecimal>> data = new HashMap<>();
}
