package com.rondinella.moneymanageapi.categories;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Categories and rules of the user who is logged in (see {@link Categories}), and the category of one movement. A
 * description never goes in a URL: the text of a rule travels in the body.
 */
@RestController
@RequestMapping(path = "/api", produces = "application/json")
public class CategoriesController {

  public record ApplyRequest(Boolean dryRun) {
  }

  private final Categories categories;

  public CategoriesController(Categories categories) {
    this.categories = categories;
  }

  @GetMapping("/categories")
  public Categories.Overview overview() {
    return categories.overview();
  }

  @PostMapping("/categories")
  public ResponseEntity<Categories.CategoryView> create(@RequestBody Categories.CategoryRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(categories.create(request));
  }

  @PutMapping("/categories/{id}")
  public Categories.CategoryView update(@PathVariable long id, @RequestBody Categories.CategoryRequest request) {
    return categories.update(id, request);
  }

  @DeleteMapping("/categories/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    categories.delete(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/categories/rules")
  public ResponseEntity<Categories.RuleView> createRule(@RequestBody Categories.RuleRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(categories.createRule(request));
  }

  @PutMapping("/categories/rules/{id}")
  public Categories.RuleView updateRule(@PathVariable long id, @RequestBody Categories.RuleRequest request) {
    return categories.updateRule(id, request);
  }

  @DeleteMapping("/categories/rules/{id}")
  public ResponseEntity<Void> deleteRule(@PathVariable long id) {
    categories.deleteRule(id);
    return ResponseEntity.noContent().build();
  }

  /** the rules on every movement not chosen by hand; {@code dryRun} (the default): only how many would change */
  @PostMapping("/categories/apply")
  public Categories.Applied apply(@RequestBody(required = false) ApplyRequest request) {
    return categories.apply(request == null || request.dryRun() == null || request.dryRun());
  }

  @PutMapping("/movements/{id}/category")
  public Categories.Assigned assign(@PathVariable long id, @RequestBody(required = false) Categories.AssignRequest request) {
    return categories.assign(id, request);
  }
}
