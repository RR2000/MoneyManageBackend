package com.rondinella.moneymanageapi.auth.oidc;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

/**
 * The authorization requests of the logins (Spring Security's default: state, nonce, PKCE), and for a link of the
 * site's OpenID provider (?link=(request), see {@link OidcLinks}) the account bound to the state.
 */
public final class OidcLinkRequests implements OAuth2AuthorizationRequestResolver {

  private final OAuth2AuthorizationRequestResolver defaults;
  private final OidcLinks links;

  public OidcLinkRequests(ClientRegistrationRepository registrations, String baseUri, OidcLinks links) {
    this.defaults = new DefaultOAuth2AuthorizationRequestResolver(registrations, baseUri);
    this.links = links;
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
    return bound(request, defaults.resolve(request));
  }

  @Override
  public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
    return bound(request, defaults.resolve(request, clientRegistrationId));
  }

  private OAuth2AuthorizationRequest bound(HttpServletRequest request, OAuth2AuthorizationRequest authorization) {
    if (authorization == null || request.getParameter(OidcLinks.PARAMETER) == null) {
      return authorization;
    }
    if (!OidcLogin.REGISTRATION_ID.equals(authorization.getAttribute(OAuth2ParameterNames.REGISTRATION_ID))) {
      // only the site's OpenID provider is linked this way
      throw new OidcLinks.Refused(OidcLinks.FAILED);
    }
    return links.bind(request, authorization);
  }
}
