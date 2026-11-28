package com.rondinella.moneymanageapi.auth.oidc;

import com.rondinella.moneymanageapi.auth.JwtService;
import com.rondinella.moneymanageapi.auth.User;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** The time limits of a link: the state stands for the account {@link OidcLinks#STATE_TTL} after the button. */
class OidcLinksTests {

  /** A clock the test moves by hand. */
  static final class TestClock extends Clock {
    Instant now = Instant.now();

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static JwtService jwt() {
    JwtService jwt = new JwtService();
    ReflectionTestUtils.invokeMethod(jwt, "init");
    return jwt;
  }

  private static OAuth2AuthorizationRequest authorization() {
    return OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://auth.example.org/oauth/v2/authorize")
        .clientId("client")
        .state("state")
        .build();
  }

  @Test
  void theStateOfALinkExpires() {
    TestClock clock = new TestClock();
    OidcLinks links = new OidcLinks(jwt(), null, new OidcLogin("both", "https://auth.example.org", "client", ""), clock);
    User user = new User();
    user.setId(42L);
    OidcLinks.Started started = links.start(user);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", OidcLogin.START_URL);
    request.setParameter(OidcLinks.PARAMETER, started.url().substring(started.url().indexOf('=') + 1));
    request.setCookies(new Cookie(OidcLinks.COOKIE, started.cookie().getValue()));
    OAuth2AuthorizationRequest bound = links.bind(request, authorization());
    assertEquals(42L, OidcLinks.linkUser(bound).orElseThrow());
    assertTrue(OidcLinks.linkUser(authorization()).isEmpty(), "a login is not a link");

    // the same link request a second time: refused
    OidcLinks.Refused again = assertThrows(OidcLinks.Refused.class, () -> links.bind(request, authorization()));
    assertEquals(OidcLinks.EXPIRED, again.code());

    // back from the provider after the state's time: refused before the account is even looked up
    clock.now = clock.now.plus(OidcLinks.STATE_TTL).plus(Duration.ofSeconds(1));
    OidcLinks.Refused late = assertThrows(OidcLinks.Refused.class, () -> links.complete(bound, "sub"));
    assertEquals(OidcLinks.EXPIRED, late.code());
  }

  @Test
  void unlinkOnlyWhileThereIsAnotherWayIn() {
    User user = new User();
    user.setIdpSubject("sub");
    assertTrue(new OidcLinks(jwt(), null, new OidcLogin("both", "https://auth.example.org", "client", ""))
        .canUnlink(user));
    assertFalse(new OidcLinks(jwt(), null, new OidcLogin("oidc", "https://auth.example.org", "client", ""))
        .canUnlink(user));
    user.setIdpSubject(null);
    assertFalse(new OidcLinks(jwt(), null, new OidcLogin("both", "https://auth.example.org", "client", ""))
        .canUnlink(user), "nothing to unlink");
  }
}
