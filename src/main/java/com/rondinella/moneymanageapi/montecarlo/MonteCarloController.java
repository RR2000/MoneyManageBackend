package com.rondinella.moneymanageapi.montecarlo;

import com.rondinella.moneymanageapi.common.dtos.GraphPointsDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "http://localhost:4200")
@RequestMapping(path = "/api/montecarlo", produces = "application/json")
public class MonteCarloController {

  private final MonteCarloService monteCarloService;

  public MonteCarloController(MonteCarloService monteCarloService) {
    this.monteCarloService = monteCarloService;
  }

  /**
   * Run Monte Carlo simulation.
   *
   * @param years       projection horizon in years (default 10)
   * @param simulations number of paths (default 10000)
   * @return GraphPointsDto with P5, P25, P50, P75, P95 percentile curves
   */
  @GetMapping
  public ResponseEntity<GraphPointsDto> simulate(
      @RequestParam(defaultValue = "10") int years,
      @RequestParam(defaultValue = "10000") int simulations) {
    return ResponseEntity.ok(monteCarloService.simulate(years, simulations));
  }
}
