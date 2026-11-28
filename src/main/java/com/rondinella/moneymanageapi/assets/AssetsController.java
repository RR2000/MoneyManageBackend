package com.rondinella.moneymanageapi.assets;

import com.rondinella.moneymanageapi.banktransactions.AccountPages;
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

import java.time.LocalDate;

/**
 * The assets of the user who is logged in (see {@link Assets}): everything with the totals, and the other assets and
 * the debts one by one. Real estate is saved through /api/realestate.
 */
@RestController
@RequestMapping(path = "/api/assets", produces = "application/json")
public class AssetsController {

  private final Assets assets;

  public AssetsController(Assets assets) {
    this.assets = assets;
  }

  private static LocalDate today() {
    return LocalDate.now(AccountPages.ZONE);
  }

  @GetMapping
  public Assets.Answer get() {
    return assets.summary(today());
  }

  @PostMapping("/others")
  public ResponseEntity<Assets.OtherAssetView> createOther(@RequestBody Assets.OtherAssetRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(assets.create(request, today()));
  }

  @PutMapping("/others/{id}")
  public Assets.OtherAssetView updateOther(@PathVariable long id, @RequestBody Assets.OtherAssetRequest request) {
    return assets.update(id, request, today());
  }

  @DeleteMapping("/others/{id}")
  public ResponseEntity<Void> deleteOther(@PathVariable long id) {
    assets.deleteOther(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/debts")
  public ResponseEntity<Assets.DebtView> createDebt(@RequestBody Assets.DebtRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(assets.create(request, today()));
  }

  @PutMapping("/debts/{id}")
  public Assets.DebtView updateDebt(@PathVariable long id, @RequestBody Assets.DebtRequest request) {
    return assets.update(id, request, today());
  }

  @DeleteMapping("/debts/{id}")
  public ResponseEntity<Void> deleteDebt(@PathVariable long id) {
    assets.deleteDebt(id);
    return ResponseEntity.noContent().build();
  }
}
