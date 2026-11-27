package com.rondinella.moneymanageapi.brokertransactions;

/**
 * Degiro exports written by hand, in the shape of the real ones (header names, the unnamed currency columns, numbers
 * with a comma in Italian and a dot in English, quotes). Made-up products, ISINs and order ids.
 */
public final class DegiroFiles {

  private DegiroFiles() {
  }

  public static final String ORDER_1 = "0a1b2c3d-0000-4000-8000-000000000001";
  public static final String ORDER_2 = "0a1b2c3d-0000-4000-8000-000000000002";

  /** Transactions.csv, Italian: an ETF in EUR, an order in USD filled twice in the same minute at the same price. */
  public static final String TRANSACTIONS_IT = """
      Data,Ora,Prodotto,ISIN,Borsa di riferimento,Sede di esecuzione,Quantità,Quotazione,,Valore locale,,Valore,,Tasso di cambio,Commissioni di transazione e/o costi di terzi,,Totale,,ID Ordine
      03-11-2026,10:15,ETF FINTO MONDO,IE0000000011,EAM,XAMS,5,"80,12",EUR,"-400,60",EUR,"-400,60",EUR,,"-2,00",EUR,"-402,60",EUR,0a1b2c3d-0000-4000-8000-000000000001
      04-11-2026,15:30,AZIONE FINTA USA,US0000000022,NDQ,XNAS,2,"150,50",USD,"-301,00",USD,"-277,15",EUR,"1,0861","-0,50",EUR,"-277,65",EUR,0a1b2c3d-0000-4000-8000-000000000002
      04-11-2026,15:30,AZIONE FINTA USA,US0000000022,NDQ,XNAS,2,"150,50",USD,"-301,00",USD,"-277,15",EUR,"1,0861","-0,50",EUR,"-277,65",EUR,0a1b2c3d-0000-4000-8000-000000000002
      """;

  /** The same executions exported in English. */
  public static final String TRANSACTIONS_EN = """
      Date,Time,Product,ISIN,Reference exchange,Venue,Quantity,Price,,Local value,,Value,,Exchange rate,Transaction and/or third party fees,,Total,,Order ID
      03-11-2026,10:15,ETF FINTO MONDO,IE0000000011,EAM,XAMS,5,80.12,EUR,-400.60,EUR,-400.60,EUR,,-2.00,EUR,-402.60,EUR,0a1b2c3d-0000-4000-8000-000000000001
      04-11-2026,15:30,AZIONE FINTA USA,US0000000022,NDQ,XNAS,2,150.50,USD,-301.00,USD,-277.15,EUR,1.0861,-0.50,EUR,-277.65,EUR,0a1b2c3d-0000-4000-8000-000000000002
      04-11-2026,15:30,AZIONE FINTA USA,US0000000022,NDQ,XNAS,2,150.50,USD,-301.00,USD,-277.15,EUR,1.0861,-0.50,EUR,-277.65,EUR,0a1b2c3d-0000-4000-8000-000000000002
      """;

  /** Account.csv, Italian: a dividend and its withholding in USD, a buy in EUR, a deposit, an FX line, a sweep. */
  public static final String ACCOUNT_IT = """
      Data,Ora,Data valore,Prodotto,ISIN,Descrizione,Tasso di cambio,Variazione,,Saldo,,ID Ordine
      05-11-2026,07:40,04-11-2026,AZIONE FINTA USA,US0000000022,Dividendo,,USD,"0,48",USD,"0,48",
      05-11-2026,07:40,04-11-2026,AZIONE FINTA USA,US0000000022,Ritenuta sul dividendo,,USD,"-0,07",USD,"0,41",
      05-11-2026,07:41,05-11-2026,,,Cambio valuta,"1,0861",,,EUR,"99,78",
      03-11-2026,10:15,03-11-2026,ETF FINTO MONDO,IE0000000011,"Acquisto 5 ETF FINTO MONDO@80,12 EUR (IE0000000011)",,EUR,"-400,60",EUR,"99,40",0a1b2c3d-0000-4000-8000-000000000001
      02-11-2026,09:00,02-11-2026,,,Deposito,,EUR,"500,00",EUR,"500,00",
      01-11-2026,09:00,01-11-2026,,,Degiro Cash Sweep Transfer,,EUR,"10,00",EUR,"10,00",
      """;

  /** Account.csv, English. */
  public static final String ACCOUNT_EN = """
      Date,Time,Value date,Product,ISIN,Description,FX,Change,,Balance,,Order Id
      05-11-2026,07:40,04-11-2026,AZIONE FINTA USA,US0000000022,Dividend,,USD,0.48,USD,0.48,
      05-11-2026,07:40,04-11-2026,AZIONE FINTA USA,US0000000022,Dividend Tax,,USD,-0.07,USD,0.41,
      02-11-2026,09:00,02-11-2026,,,Deposit,,EUR,500.00,EUR,500.00,
      """;
}
