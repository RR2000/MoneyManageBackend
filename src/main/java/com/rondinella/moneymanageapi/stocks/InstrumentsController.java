package com.rondinella.moneymanageapi.stocks;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Where the prices of each investment come from (see {@link Instruments}). */
@RestController
@RequestMapping(path = "/api/instruments", produces = "application/json")
public class InstrumentsController {

  public record SymbolRequest(String symbol) {
  }

  private final Instruments instruments;

  public InstrumentsController(Instruments instruments) {
    this.instruments = instruments;
  }

  /** The ISINs of the user, and whether Alpha Vantage is on and how many requests it has left today. */
  @GetMapping
  public Map<String, Object> list() {
    List<Instruments.View> list = instruments.list();
    return Map.of("instruments", list, "alphaVantage", instruments.alphaVantageEnabled(),
        "alphaVantageLeftToday", instruments.alphaVantageLeftToday());
  }

  @GetMapping("/preview")
  public Instruments.Preview preview(@RequestParam String symbol) {
    return instruments.preview(symbol);
  }

  @PutMapping("/{isin}/symbol")
  public Instruments.View setSymbol(@PathVariable String isin, @RequestBody SymbolRequest request) {
    return instruments.setSymbol(isin, request == null ? "" : request.symbol());
  }

  /** CSV of prices, "date,price" per line. */
  @PostMapping(path = "/{isin}/prices", consumes = "text/csv")
  public Map<String, Integer> importPrices(@PathVariable String isin, @RequestBody String csv) {
    return Map.of("imported", instruments.importCsv(isin, csv));
  }
}
