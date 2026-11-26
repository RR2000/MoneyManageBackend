package com.rondinella.moneymanageapi.stocks;

import com.rondinella.moneymanageapi.tenant.TenantContext;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** The OpenFIGI answer for one ISIN, shared by everybody (public market data). */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "isin_listing", schema = TenantContext.SHARED)
public class IsinListing {
  @Id
  private String isin;
  /** JSON array of {ticker, exchange, name} */
  private String listings;
  private Instant fetchedAt;
}
