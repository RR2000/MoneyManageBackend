package com.rondinella.moneymanageapi.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class GraphPointsDto implements Serializable {
  List<String> xLabels = new ArrayList<>();
  List<String> lineNames = new ArrayList<>();
  Map<String, Map<String, BigDecimal>> data = new HashMap<>();
}
