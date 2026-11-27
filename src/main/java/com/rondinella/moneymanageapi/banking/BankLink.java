package com.rondinella.moneymanageapi.banking;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** One consent given at a bank (an Enable Banking session), in the user's schema. */
@Getter
@Setter
@Entity
public class BankLink {

  public enum Status {
    /** Consent valid: synced twice a day. */
    ACTIVE,
    /** Consent over (valid_until passed, or the bank says so): a new link is needed. */
    EXPIRED,
    /** Consent withdrawn at the bank. */
    REVOKED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;
  String aspspName;
  String aspspCountry;
  /** The key to the bank data: never returned, never logged. */
  String sessionId;
  @Enumerated(EnumType.STRING)
  Status status;
  Instant validUntil;
  Instant createdAt;
  Instant lastSyncAt;
  /** Error code of the last sync (Enable Banking's, e.g. ASPSP_RATE_LIMIT_EXCEEDED), null when it went well. */
  String lastError;
  /** Movements of the last sync that could have been more than one row already there: added, not matched. */
  int lastAmbiguous;
  /** After a rate limit of the bank: no sync before this. */
  Instant retryAfter;
  Instant expiryMailSentAt;
}
