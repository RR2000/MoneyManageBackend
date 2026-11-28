package com.rondinella.moneymanageapi.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * "Prova la demo": a sandbox of its own for every visitor, with the sample data, by one click and without a password
 * (DemoAccounts). Off unless MONEY_DEMO_ENABLED=true; then POST /api/demo makes one, at most {@link #perIp()} an hour
 * from one address and {@link #maxActive()} open at the same time, each for {@link #lifetime()}.
 */
@Component
public class DemoSettings {

  private final boolean enabled;
  private final int perIp;
  private final int maxActive;
  private final Duration lifetime;
  private final long maxUploadBytes;

  public DemoSettings(@Value("${app.demo.enabled:false}") boolean enabled,
                      @Value("${app.demo.per-ip:3}") int perIp,
                      @Value("${app.demo.max-active:50}") int maxActive,
                      @Value("${app.demo.hours:24}") int hours,
                      @Value("${app.demo.max-upload-kb:256}") int maxUploadKb) {
    if (perIp < 1 || maxActive < 1 || hours < 1 || maxUploadKb < 1) {
      throw new IllegalStateException("app.demo: per-ip, max-active, hours and max-upload-kb must be at least 1");
    }
    this.enabled = enabled;
    this.perIp = perIp;
    this.maxActive = maxActive;
    this.lifetime = Duration.ofHours(hours);
    this.maxUploadBytes = maxUploadKb * 1024L;
  }

  public boolean enabled() {
    return enabled;
  }

  /** sandboxes one address can make in an hour */
  public int perIp() {
    return perIp;
  }

  /** sandboxes open at the same time, for the whole site */
  public int maxActive() {
    return maxActive;
  }

  public Duration lifetime() {
    return lifetime;
  }

  /** the largest file a sandbox can upload (bank and broker exports, prices) */
  public long maxUploadBytes() {
    return maxUploadBytes;
  }

  public int maxUploadKb() {
    return (int) (maxUploadBytes / 1024);
  }
}
