package com.rondinella.moneymanageapi.auth.oidc;

import com.rondinella.moneymanageapi.auth.AuthService;
import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.auth.mail.EmailLinks;
import com.rondinella.moneymanageapi.auth.mail.EmailToken;
import com.rondinella.moneymanageapi.auth.mail.EmailTokenRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
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
 * Who a login with the site's OpenID provider is here. In order:
 * <ol>
 *   <li>the account already linked to the provider's subject (sub);</li>
 *   <li>otherwise, only with email_verified=true: the account whose confirmed email is the same, which gets linked
 *       (refused if it is already linked to another subject);</li>
 *   <li>otherwise, if a confirmation link for that email is still pending on some account, refused: that account is
 *       waiting for its owner, who confirms it (or logs in with the password) first;</li>
 *   <li>otherwise a new account with its own schema, as at sign-up, with the verified email.</li>
 * </ol>
 * Refusals are OAuth2 errors whose code reaches the web app (/login#oidc-error=code), see {@link #ERRORS}.
 */
@Service
public class OidcAccounts implements OAuth2UserService<OidcUserRequest, OidcUser> {

  public static final String EMAIL_UNVERIFIED = "email_unverified";
  public static final String EMAIL_UNCONFIRMED = "email_unconfirmed";
  public static final String ALREADY_LINKED = "already_linked";
  /** The codes the web app knows; any other failure is shown as a generic one. */
  public static final Set<String> ERRORS = Set.of(EMAIL_UNVERIFIED, EMAIL_UNCONFIRMED, ALREADY_LINKED);

  private final UserRepository users;
  private final EmailTokenRepository tokens;
  private final AuthService authService;
  private final OAuth2UserService<OidcUserRequest, OidcUser> provider = new OidcUserService();
  private final Clock clock = Clock.systemUTC();

  public OidcAccounts(UserRepository users, EmailTokenRepository tokens, AuthService authService) {
    this.users = users;
    this.tokens = tokens;
    this.authService = authService;
  }

  /** The id token is already checked here (signature, issuer, audience, nonce, expiry). */
  @Override
  public OidcUser loadUser(OidcUserRequest request) {
    OidcUser idp = provider.loadUser(request);
    User user = toLocalUser(idp.getSubject(), idp.getEmail(), Boolean.TRUE.equals(idp.getEmailVerified()),
        idp.getPreferredUsername());
    return new LocalOidcUser(AuthorityUtils.createAuthorityList("ROLE_" + user.getRole()), idp.getIdToken(),
        idp.getUserInfo(), user.getUsername());
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

  /** The provider's user, named as the local account (the name the app's token is made for). */
  static final class LocalOidcUser extends DefaultOidcUser {

    private final String username;

    LocalOidcUser(Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken, OidcUserInfo userInfo,
                  String username) {
      super(authorities, idToken, userInfo);
      this.username = username;
    }

    @Override
    public String getName() {
      return username;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof LocalOidcUser other && super.equals(o) && username.equals(other.username);
    }

    @Override
    public int hashCode() {
      return 31 * super.hashCode() + username.hashCode();
    }
  }
}
