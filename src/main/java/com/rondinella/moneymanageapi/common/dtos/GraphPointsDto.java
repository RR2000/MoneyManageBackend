package com.rondinella.moneymanageapi.common.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.*;

@Getter
@Setter
@AllArgsConstructor
public class GraphPointsDto implements Serializable {
  private final Map<String, Map<String, BigDecimal>> graphData = new TreeMap<>();
  private final Set<String> uniqueLabels = new TreeSet<>();
  private final Set<String> uniqueGraphNames = new TreeSet<>();

  public void addPoint(String graphName, String pointName, BigDecimal value) {
    graphData.computeIfAbsent(graphName, k -> new TreeMap<>()).put(pointName, value);
    uniqueLabels.add(pointName);
    uniqueGraphNames.add(graphName);
  }

  public void addPoints(String graphName, Map<String, BigDecimal> points) {
    if (!graphData.containsKey(graphName)) {
      uniqueGraphNames.add(graphName);
      graphData.put(graphName, new TreeMap<>());
    }

    for (Map.Entry<String, BigDecimal> entry : points.entrySet()) {
      String pointName = entry.getKey();
      BigDecimal value = entry.getValue();
      graphData.get(graphName).put(pointName, value);
      uniqueLabels.add(pointName);
    }
  }

  public BigDecimal getPointValue(String graphName, String label) {
    Map<String, BigDecimal> graph = graphData.get(graphName);
    return graph != null && graph.containsKey(label) ? graph.get(label) : new BigDecimal("-100000");
  }

  public Map<String, BigDecimal> getGraph(String graphName) {
    return graphData.get(graphName);
  }

  public Map<String, BigDecimal> getLabelValues(String label) {
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    uniqueGraphNames.forEach(graphName -> {
      BigDecimal value = getPointValue(graphName, label);
      if (value != null) {
        result.put(graphName, value);
      }
    });
    return result;
  }

  public void removeLabel(String label) {
    uniqueLabels.remove(label);
    graphData.values().forEach(graph -> graph.remove(label));
  }

  public void setLabels(List<String> labels) {
    uniqueLabels.clear();
    uniqueLabels.addAll(labels);
    validateAndFillMissingValues();
  }

  public void removeOrphanLabels() {
    Set<String> labelsToRemove = new HashSet<>();
    for (String label : uniqueLabels) {
      boolean allNull = true;
      for (String graphName : uniqueGraphNames) {
        Map<String, BigDecimal> graph = graphData.get(graphName);
        if (graph != null && graph.containsKey(label) && graph.get(label) != null) {
          allNull = false;
          break;
        }
      }
      if (allNull) {
        labelsToRemove.add(label);
      }
    }
    uniqueLabels.removeAll(labelsToRemove);
  }

  public void add(GraphPointsDto otherDto) {
    for (Map.Entry<String, Map<String, BigDecimal>> entry : otherDto.graphData.entrySet()) {
      String graphName = entry.getKey();
      Map<String, BigDecimal> points = entry.getValue();

      if (!graphData.containsKey(graphName)) {
        uniqueGraphNames.add(graphName);
        graphData.put(graphName, new TreeMap<>());
      }

      for (Map.Entry<String, BigDecimal> pointEntry : points.entrySet()) {
        String pointName = pointEntry.getKey();
        BigDecimal value = pointEntry.getValue();
        graphData.get(graphName).put(pointName, value);
        uniqueLabels.add(pointName);
      }
    }
  }

  public void SetLabelsAndFillMissingValues(List<String>labels){
    validateAndFillMissingValues();
    setLabels(labels);
  }

  public void validateAndFillMissingValues() {
    removeOrphanLabels();
    for (String graphName : uniqueGraphNames) {
      Map<String, BigDecimal> graph = graphData.get(graphName);
      for (String label : uniqueLabels) {
        if (!graph.containsKey(label)) {
          BigDecimal previousValue = getPreviousValue(graph, label);
          if (previousValue != null) {
            graph.put(label, previousValue);
          }
        }
      }
    }
  }

  public BigDecimal getPreviousValue(Map<String, BigDecimal> graph, String label) {
    List<String> labels = new ArrayList<>(uniqueLabels);
    int labelIndex = labels.indexOf(label);

    // Start from the current label and move to the previous labels
    for (int i = labelIndex - 1; i >= 0; i--) {
      String prevLabel = labels.get(i);
      BigDecimal previousValue = graph.get(prevLabel);
      if (previousValue != null) {
        return previousValue;
      }
    }

    // If no non-null value is found in previous labels, return null
    return null;
  }

  public String getFirstLabel() {
    return ((TreeSet<String>)uniqueLabels).first();
  }

  public String getLastLabel() {
    return ((TreeSet<String>)uniqueLabels).last();
  }

  public void addTotalColumn(String totalColumnName, String... graphNames) {
    addTotalColumn(totalColumnName, Arrays.asList(graphNames));
  }

  public void addTotalColumn(String totalColumnName, List<String> graphNames) {
    validateAndFillMissingValues();
    Map<String, BigDecimal> totalColumn = new LinkedHashMap<>();

    // Iterate over each label
    for (String label : uniqueLabels) {
      BigDecimal labelSum = BigDecimal.ZERO;

      // Iterate over each graph name
      for (String graphName : graphNames) {
        Map<String, BigDecimal> graph = graphData.get(graphName);
        if (graph != null && graph.containsKey(label)) {
          BigDecimal value = graph.get(label);
          if (value != null) {
            labelSum = labelSum.add(value);
          }
        }
      }

      totalColumn.put(label, labelSum);
    }

    addPoints(totalColumnName, totalColumn);
  }
}
