package com.rondinella.moneymanageapi.realestate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@CrossOrigin(origins = "http://localhost:4200")
@RequestMapping(path = "/api/realestate", produces = "application/json")
public class RealEstateController {

  private final RealEstateService realEstateService;

  public RealEstateController(RealEstateService realEstateService) {
    this.realEstateService = realEstateService;
  }

  @GetMapping
  public ResponseEntity<List<RealEstate>> getAll() {
    return ResponseEntity.ok(realEstateService.findAll());
  }

  @GetMapping("/{id}")
  public ResponseEntity<RealEstate> getById(@PathVariable Long id) {
    return ResponseEntity.ok(realEstateService.findById(id));
  }

  @PostMapping
  public ResponseEntity<RealEstate> create(@RequestBody RealEstateDto dto) {
    return ResponseEntity.status(HttpStatus.CREATED).body(realEstateService.save(dto));
  }

  @PutMapping("/{id}")
  public ResponseEntity<RealEstate> update(@PathVariable Long id, @RequestBody RealEstateDto dto) {
    return ResponseEntity.ok(realEstateService.update(id, dto));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    realEstateService.delete(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/worth")
  public ResponseEntity<BigDecimal> totalWorth() {
    return ResponseEntity.ok(realEstateService.totalWorth());
  }
}
