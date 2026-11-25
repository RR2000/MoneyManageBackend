package com.rondinella.moneymanageapi.auth.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;

/** "Accedi con GitHub", on only when GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET are set (a GitHub OAuth App). */
@Component
public class GitHubLogin {

  /** Under /api: in production only /api reaches the backend (the web app's nginx serves the rest). */
  public static final String AUTHORIZATION_BASE = "/api/oauth2/authorization";
  public static final String CALLBACK_BASE = "/api/login/oauth2/code";

  private final String clientId;
  private final String clientSecret;

  public GitHubLogin(@Value("${app.github.client-id:}") String clientId,
                     @Value("${app.github.client-secret:}") String clientSecret) {
    this.clientId = clientId == null ? "" : clientId.trim();
    this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
  }

  public boolean enabled() {
    return !clientId.isEmpty() && !clientSecret.isEmpty();
  }

  /** Callback to register on the OAuth App: https://(site)/api/login/oauth2/code/github */
  public ClientRegistration registration() {
    return CommonOAuth2Provider.GITHUB.getBuilder("github")
        .clientId(clientId)
        .clientSecret(clientSecret)
        .redirectUri("{baseUrl}" + CALLBACK_BASE + "/{registrationId}")
        .scope("read:user", "user:email")
        .build();
  }
}
