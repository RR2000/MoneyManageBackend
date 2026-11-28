package com.rondinella.moneymanageapi.auth.oidc;

import com.rondinella.moneymanageapi.auth.AuthService;
import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.auth.mail.EmailLinks;
import com.rondinella.moneymanageapi.auth.mail.EmailToken;
import com.rondinella.moneymanageapi.auth.mail.EmailTokenRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Who a login with the site's OpenID provider is here. A link started from the settings page ({@link OidcLinks}):
 * the account the state was bound to, whatever the emails. Otherwise, in order:
 * <ol>
 *   <li>the account already linked to the provider's subject (sub);</li>
 *   <li>otherwise, only with email_verified=true: the account whose confirmed email is the same, which gets linked
 *       (refused if it is already linked to another subject);</li>
 *   <li>otherwise, if a confirmation link for that email is still pending on some account, refused: that account is
 *       waiting for its owner, who confirms it (or logs in with the password) first;</li>
 *   <li>otherwise a new account with its own schema, as at sign-up, with the verified email.</li>
 * </ol>
 * Refusals are OAuth2 errors whose code reaches the web app (/login#oidc-error=code), see {@link #ERRORS}; those of a
 * link reach the settings page ({@link OidcLinks.Refused}).
 * <p>
 * It runs once the provider's answer is checked (code exchanged with PKCE; id token signature, issuer, audience,
 * nonce, expiry; userinfo), as the login filter's result converter: there the authorization request, and with it
 * whether this is a link, is known.
 */
@Service
public class OidcAccounts {

  public static final String EMAIL_UNVERIFIED = "email_unverified";
  public static final String EMAIL_UNCONFIRMED = "email_unconfirmed";
  public static final String ALREADY_LINKED = "already_linked";
  /** The codes the web app knows; any other failure is shown as a generic one. */
  public static final Set<String> ERRORS = Set.of(EMAIL_UNVERIFIED, EMAIL_UNCONFIRMED, ALREADY_LINKED);

  private final UserRepository users;
  private final EmailTokenRepository tokens;
  private final AuthService authService;
  private final OidcLinks links;
  private final Clock clock = Clock.systemUTC();

  public OidcAccounts(UserRepository users, EmailTokenRepository tokens, AuthService authService, OidcLinks links) {
    this.users = users;
    this.tokens = tokens;
    this.authService = authService;
    this.links = links;
  }

  /**
   * The login's result: for the site's OpenID provider the local account (named as the account, so the app's token is
   * made for it); any other provider (GitHub) as Spring Security makes it.
   */
  public OAuth2AuthenticationToken authenticate(OAuth2LoginAuthenticationToken result) {
    String registrationId = result.getClientRegistration().getRegistrationId();
    if (!OidcLogin.REGISTRATION_ID.equals(registrationId) || !(result.getPrincipal() instanceof OidcUser idp)) {
      return new OAuth2AuthenticationToken(result.getPrincipal(), result.getAuthorities(), registrationId);
    }
    OAuth2AuthorizationRequest authorization = result.getAuthorizationExchange().getAuthorizationRequest();
    boolean link = OidcLinks.linkUser(authorization).isPresent();
    User user = link
        ? links.complete(authorization, idp.getSubject())
        : toLocalUser(idp.getSubject(), idp.getEmail(), Boolean.TRUE.equals(idp.getEmailVerified()),
        idp.getPreferredUsername());
    LocalOidcUser local = new LocalOidcUser(AuthorityUtils.createAuthorityList("ROLE_" + user.getRole()),
        idp.getIdToken(), idp.getUserInfo(), user.getUsername(), link);
    return new OAuth2AuthenticationToken(local, local.getAuthorities(), registrationId);
  }

  /** Separate from the HTTP calls, so it can be tested without a provider. */
  public User toLocalUser(String subject, String rawEmail, boolean emailVerified, String preferredUsername) {
    if (subject == null || subject.isBlank()) {
      throw refused("invalid_subject");
    }
    Optional<User> linked = users.findByIdpSubject(subject);
    if (linked.isPresent()) {
      return linked.get();
    }
    String email = EmailLinks.normalize(rawEmail);
    if (!emailVerified || !EmailLinks.validEmail(email)) {
      throw refused(EMAIL_UNVERIFIED);
    }
    Optional<User> sameEmail = users.findByEmail(email);
    if (sameEmail.isPresent()) {
      User user = sameEmail.get();
      if (user.getIdpSubject() != null) {
        throw refused(ALREADY_LINKED);
      }
      user.setIdpSubject(subject);
      return users.save(user);
    }
    if (tokens.pending(email, EmailToken.Purpose.VERIFY, clock.instant())) {
      throw refused(EMAIL_UNCONFIRMED);
    }
    return authService.createAccount(authService.freeUsername(usernameFrom(preferredUsername, email), "id"), null,
        email, subject);
  }

  /** The provider's username, or the part of the email before the @ (never the whole email: it would be shown). */
  private static String usernameFrom(String preferred, String email) {
    String name = preferred == null ? "" : preferred.trim();
    if (name.isEmpty() || name.contains("@")) {
      name = email.substring(0, email.indexOf('@'));
    }
    return name;
  }

  private static OAuth2AuthenticationException refused(String code) {
    return new OAuth2AuthenticationException(new OAuth2Error(code), code);
  }

  /**
   * The provider's user, named as the local account (the name the app's token is made for); {@code link} if it was a
   * link from the settings page (the web app goes back there).
   */
  public static final class LocalOidcUser extends DefaultOidcUser {

    private final String username;
    private final boolean link;

    LocalOidcUser(Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken, OidcUserInfo userInfo,
                  String username, boolean link) {
      super(authorities, idToken, userInfo);
      this.username = username;
      this.link = link;
    }

    @Override
    public String getName() {
      return username;
    }

    public boolean link() {
      return link;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof LocalOidcUser other && super.equals(o) && username.equals(other.username)
          && link == other.link;
    }

    @Override
    public int hashCode() {
      return 31 * (31 * super.hashCode() + username.hashCode()) + Boolean.hashCode(link);
    }
  }
}
