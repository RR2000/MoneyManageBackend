package com.rondinella.moneymanageapi.auth.mail;

import com.rondinella.moneymanageapi.tenant.TenantContext;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * A link sent by email. Only the SHA-256 of the random token is stored: whoever reads the database cannot use it.
 * Single use ({@code usedAt}) and short lived ({@code expiresAt}).
 */
@Entity
@Table(name = "email_tokens", schema = TenantContext.SHARED)
public class EmailToken {

  public enum Purpose { RESET, VERIFY }

  @Id
  @Column(name = "token_hash", length = 64)
  private String tokenHash;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Purpose purpose;

  /** where the link was sent; for VERIFY the address that becomes the account's email */
  @Column(nullable = false, length = 320)
  private String email;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "used_at")
  private Instant usedAt;

  protected EmailToken() {
  }

  public EmailToken(String tokenHash, Long userId, Purpose purpose, String email, Instant createdAt, Instant expiresAt) {
    this.tokenHash = tokenHash;
    this.userId = userId;
    this.purpose = purpose;
    this.email = email;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Long getUserId() {
    return userId;
  }

  public Purpose getPurpose() {
    return purpose;
  }

  public String getEmail() {
    return email;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getUsedAt() {
    return usedAt;
  }
}
