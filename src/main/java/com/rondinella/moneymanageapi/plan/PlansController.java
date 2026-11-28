package com.rondinella.moneymanageapi.plan;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The plans of the user who is logged in (see {@link Plans}). Amounts travel in the body, never in a URL. */
@RestController
@RequestMapping(path = "/api/plans", produces = "application/json")
public class PlansController {

  private final Plans plans;

  public PlansController(Plans plans) {
    this.plans = plans;
  }

  /** every plan with planned against real, and the inputs from money */
  @GetMapping
  public Plans.Overview overview() {
    return plans.overview();
  }

  /** a new plan (version 1); also "duplicate as a scenario": a new plan from the values of another */
  @PostMapping
  public ResponseEntity<Plans.PlanView> create(@RequestBody(required = false) Plans.PlanRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(plans.create(request));
  }

  /** {@code version}: an older version (default: the one in use) */
  @GetMapping("/{id}")
  public Plans.PlanView get(@PathVariable long id, @RequestParam(required = false) Integer version) {
    return plans.get(id, version);
  }

  /** saves a new version, the ones before stay */
  @PutMapping("/{id}")
  public Plans.PlanView save(@PathVariable long id, @RequestBody(required = false) Plans.PlanRequest request) {
    return plans.save(id, request);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    plans.delete(id);
    return ResponseEntity.noContent().build();
  }

  /** the projection of the values given, from today, without saving; {@code monteCarlo}: also the band */
  @PostMapping("/projection")
  public Plans.ProjectionView projection(@RequestBody(required = false) Plans.ProjectionRequest request) {
    return plans.project(request);
  }
}
