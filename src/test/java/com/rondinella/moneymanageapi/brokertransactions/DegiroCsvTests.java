package com.rondinella.moneymanageapi.brokertransactions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Degiro exports read by column name, in Italian and English, each amount with its own currency. */
class DegiroCsvTests {

  private static void same(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " vs " + actual);
  }

  @Test
  void transactionsInItalianAndEnglishGiveTheSameExecutions() {
    List<DegiroCsv.Trade> it = DegiroCsv.trades(DegiroFiles.TRANSACTIONS_IT);
    List<DegiroCsv.Trade> en = DegiroCsv.trades(DegiroFiles.TRANSACTIONS_EN);
    assertEquals(3, it.size());
    assertEquals(it, en);

    DegiroCsv.Trade etf = it.getFirst();
    assertEquals(Timestamp.valueOf("2026-11-03 10:15:00"), etf.datetime());
    assertEquals("ETF FINTO MONDO", etf.product());
    assertEquals("IE0000000011", etf.isin());
    same("5", etf.quantity());
    same("80.12", etf.price());
    assertEquals("EUR", etf.currency());
    same("2.00", etf.fee());
    assertEquals("EUR", etf.feeCurrency());
    assertEquals(DegiroFiles.ORDER_1, etf.orderId());

    DegiroCsv.Trade usd = it.get(1);
    same("150.50", usd.price());
    assertEquals("USD", usd.currency(), "the currency of the price, not converted");
    same("0.50", usd.fee());
    assertEquals("EUR", usd.feeCurrency());
    assertEquals(DegiroFiles.ORDER_2, usd.orderId());
  }

  @Test
  void columnsAreFoundByNameInAnyOrderAndInOtherLanguages() {
    String german = """
        Order-ID,Uhrzeit,Datum,Produkt,ISIN,Anzahl,Kurs,,Transaktionskosten und/oder Kosten Dritter,
        abc-1,09:05,07-11-2026,ETF,IE0000000011,-3,"12,5",USD,"-1,00",EUR
        """;
    DegiroCsv.Trade t = DegiroCsv.trades(german).getFirst();
    assertEquals(Timestamp.valueOf("2026-11-07 09:05:00"), t.datetime());
    same("-3", t.quantity());
    same("12.5", t.price());
    assertEquals("USD", t.currency());
    same("1.00", t.fee());
    assertEquals("abc-1", t.orderId());

    // the short header money always read: no currency, no order id
    DegiroCsv.Trade old = DegiroCsv.trades("Data,Ora,Prodotto,ISIN,Quantità,Quotazione,Commissioni di\n"
        + "01-02-2025,10:00,MSCI WORLD,IE0000000011,10,50,2\n").getFirst();
    assertNull(old.currency());
    assertNull(old.orderId());
    same("-2", old.fee());
    same("50", old.price());

    // semicolons, as a spreadsheet saves it again
    assertEquals(3, DegiroCsv.trades(DegiroFiles.TRANSACTIONS_EN.replace(',', ';')).size());
  }

  @Test
  void numbersWithCommaOrDotAndThousands() {
    same("1234.5", DegiroCsv.number("1.234,5"));
    same("1234.5", DegiroCsv.number("1,234.5"));
    same("-0.07", DegiroCsv.number("-0,07"));
    same("12", DegiroCsv.number("12"));
  }

  @Test
  void theAccountStatementKeepsTheCurrencyOfEveryRow() {
    List<DegiroCsv.CashMovement> it = DegiroCsv.account(DegiroFiles.ACCOUNT_IT);
    assertEquals(4, it.size(), "no FX line without amount, no cash sweep");
    DegiroCsv.CashMovement dividend = it.getFirst();
    assertEquals("Dividendo", dividend.description());
    assertEquals(Timestamp.valueOf("2026-11-04 07:40:00"), dividend.datetime(), "value date and time");
    same("0.48", dividend.amount());
    assertEquals("USD", dividend.currency());
    same("-0.07", it.get(1).amount());
    assertEquals("USD", it.get(1).currency());
    assertEquals("EUR", it.get(2).currency());
    same("-400.60", it.get(2).amount());
    assertEquals("Acquisto 5 ETF FINTO MONDO@80,12 EUR (IE0000000011)", it.get(2).description());

    List<DegiroCsv.CashMovement> en = DegiroCsv.account(DegiroFiles.ACCOUNT_EN);
    assertEquals(3, en.size());
    assertEquals("USD", en.get(1).currency());
    same("-0.07", en.get(1).amount());
    assertEquals("Dividend Tax", en.get(1).description());
  }

  @Test
  void somethingElseIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> DegiroCsv.trades("a,b\nx,1\n"));
    assertThrows(IllegalArgumentException.class, () -> DegiroCsv.account(DegiroFiles.TRANSACTIONS_IT));
    assertThrows(IllegalArgumentException.class, () -> DegiroCsv.trades(""));
  }
}
