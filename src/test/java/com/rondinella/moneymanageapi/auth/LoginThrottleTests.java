package com.rondinella.moneymanageapi.auth;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class LoginThrottleTests {

  /** A clock the test moves by hand. */
  static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void aUsernameIsFreeAgainAfterTheWindow() {
    TestClock clock = new TestClock();
    LoginThrottle throttle = new LoginThrottle(3, 100, Duration.ofMinutes(15), clock);
    for (int i = 0; i < 3; i++) {
      throttle.checkLogin("Bob");
      throttle.loginFailed("Bob");
    }
    assertThrows(ResponseStatusException.class, () -> throttle.checkLogin("bob"));
    throttle.checkLogin("alice");
    clock.now = clock.now.plus(Duration.ofMinutes(16));
    throttle.checkLogin("bob");
  }

  @Test
  void aGoodLoginClearsTheCount() {
    LoginThrottle throttle = new LoginThrottle(3, 100, Duration.ofMinutes(15), new TestClock());
    throttle.loginFailed("bob");
    throttle.loginFailed("bob");
    throttle.loginSucceeded("bob");
    throttle.loginFailed("bob");
    throttle.checkLogin("bob");
  }

  @Test
  void registrationsAreCappedForTheWholeSite() {
    TestClock clock = new TestClock();
    LoginThrottle throttle = new LoginThrottle(5, 2, Duration.ofMinutes(15), clock);
    throttle.register();
    throttle.register();
    ResponseStatusException e = assertThrows(ResponseStatusException.class, throttle::register);
    assertEquals(429, e.getStatusCode().value());
    clock.now = clock.now.plus(Duration.ofMinutes(16));
    throttle.register();
  }
}
