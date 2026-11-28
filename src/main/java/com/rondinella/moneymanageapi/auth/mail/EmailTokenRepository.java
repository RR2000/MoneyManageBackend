package com.rondinella.moneymanageapi.auth.mail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface EmailTokenRepository extends JpaRepository<EmailToken, String> {

  /** Uses the token if it is still good: 1 the first time, 0 if used, expired or unknown (also when two race). */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update EmailToken t set t.usedAt = :now where t.tokenHash = :hash and t.purpose = :purpose"
      + " and t.usedAt is null and t.expiresAt > :now")
  int use(@Param("hash") String hash, @Param("purpose") EmailToken.Purpose purpose, @Param("now") Instant now);

  /** The other links of the same kind of an account stop working (after a reset, or a confirmed email). */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update EmailToken t set t.usedAt = :now where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
  int useAll(@Param("userId") Long userId, @Param("purpose") EmailToken.Purpose purpose, @Param("now") Instant now);

  /** A link to confirm this email on some account is out and still good (the account is waiting for its owner). */
  @Query("select count(t) > 0 from EmailToken t where t.email = :email and t.purpose = :purpose"
      + " and t.usedAt is null and t.expiresAt > :now")
  boolean pending(@Param("email") String email, @Param("purpose") EmailToken.Purpose purpose, @Param("now") Instant now);

  @Modifying
  @Query("delete from EmailToken t where t.expiresAt < :before")
  int deleteExpiredBefore(@Param("before") Instant before);
}
