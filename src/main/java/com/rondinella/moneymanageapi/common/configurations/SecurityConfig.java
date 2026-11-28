package com.rondinella.moneymanageapi.common.configurations;

import com.rondinella.moneymanageapi.auth.AuthService;
import com.rondinella.moneymanageapi.auth.github.GitHubLogin;
import com.rondinella.moneymanageapi.auth.github.GitHubUserService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import com.rondinella.moneymanageapi.auth.oidc.OidcAccounts;
import com.rondinella.moneymanageapi.auth.oidc.OidcLinkRequests;
import com.rondinella.moneymanageapi.auth.oidc.OidcLinks;
import com.rondinella.moneymanageapi.auth.oidc.OidcLogin;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import com.rondinella.moneymanageapi.auth.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                 JwtAuthenticationFilter jwtAuthenticationFilter,
                                                 AuthenticationProvider authenticationProvider,
                                                 CorsConfigurationSource corsConfigurationSource,
                                                 GitHubLogin gitHub,
                                                 GitHubUserService gitHubUsers,
                                                 OidcLogin oidc,
                                                 OidcAccounts oidcAccounts,
                                                 OidcLinks oidcLinks,
                                                 AuthService authService) throws Exception {
    http
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        // Stateless token API: CSRF tokens are not applicable to the JSON
        // endpoints, which are authenticated solely via the Authorization
        // header (no cookies/session). CSRF stays active for any other path.
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**").permitAll()
            // the bank sends the browser back without a login: the one-time state says whose it is
            .requestMatchers(HttpMethod.GET, "/api/banking/callback").permitAll()
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            .requestMatchers("/actuator/health/**").permitAll()
            .requestMatchers("/error").permitAll()
            .anyRequest().authenticated())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // no token or an expired one: 401, the web app goes back to the login page
        .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .authenticationProvider(authenticationProvider)
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    // GitHub (not with AUTH_MODE=oidc) and the site's OpenID provider (AUTH_MODE both or oidc, OidcLogin) send the
    // browser back to the backend, which gives the web app its token in the URL fragment (never sent to a server, so
    // it does not end up in access logs); for the OpenID provider also the id token, for its logout
    boolean withGitHub = gitHub.enabled() && oidc.passwordLogin();
    if (withGitHub || oidc.enabled()) {
      ClientRegistration gitHubRegistration = withGitHub ? gitHub.registration() : null;
      ClientRegistrationRepository registrations = id -> switch (id) {
        case "github" -> gitHubRegistration;
        case OidcLogin.REGISTRATION_ID -> oidc.registration();
        default -> null;
      };
      http.oauth2Login(oauth2 -> oauth2
          .loginPage("/login")
          .clientRegistrationRepository(registrations)
          // "Collega a raoulrondinella.dev" (OidcLinks): ?link=(request) binds the state to the logged-in account
          .authorizationEndpoint(a -> a.baseUri(GitHubLogin.AUTHORIZATION_BASE)
              .authorizationRequestResolver(
                  new OidcLinkRequests(registrations, GitHubLogin.AUTHORIZATION_BASE, oidcLinks)))
          .redirectionEndpoint(r -> r.baseUri(GitHubLogin.CALLBACK_BASE + "/*"))
          // the site's OpenID provider: Spring Security's checks, then OidcAccounts decides the account (below)
          .userInfoEndpoint(u -> u.userService(gitHubUsers))
          .successHandler((request, response, authentication) -> {
            Object principal = authentication.getPrincipal();
            if (principal instanceof OidcAccounts.LocalOidcUser user && user.link()) {
              // linked from the settings page, already logged in there: back to it (the id token for the logout)
              response.sendRedirect("/settings#oidc-link=linked&id_token=" + user.getIdToken().getTokenValue());
              return;
            }
            String token = authService.tokenFor(((OAuth2User) principal).getName());
            String fragment = "token=" + token;
            if (principal instanceof OidcUser user) {
              fragment += "&id_token=" + user.getIdToken().getTokenValue();
            }
            response.sendRedirect("/login#" + fragment);
          })
          .failureHandler((request, response, exception) -> response.sendRedirect(
              exception instanceof OidcLinks.Refused refused
                  ? "/settings#oidc-link-error=" + refused.code()
                  : request.getRequestURI().endsWith("/" + OidcLogin.REGISTRATION_ID)
                  ? "/login#oidc-error=" + oidcErrorCode(exception)
                  : "/login#github-error"))
          // the provider cannot be reached (no registration): back to the login page, not an error page; a link
          // request that is not good (or the provider down during a link): back to the settings page
          .withObjectPostProcessor(new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
            @Override
            public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
              filter.setAuthenticationFailureHandler((request, response, exception) -> {
                Throwable cause = exception;
                while (cause != null && !(cause instanceof OidcLinks.Refused)) {
                  cause = cause.getCause();
                }
                if (cause instanceof OidcLinks.Refused refused) {
                  response.sendRedirect("/settings#oidc-link-error=" + refused.code());
                } else if (request.getParameter(OidcLinks.PARAMETER) != null) {
                  response.sendRedirect("/settings#oidc-link-error=unavailable");
                } else {
                  response.sendRedirect("/login#oidc-error=unavailable");
                }
              });
              return filter;
            }
          })
          // who the provider's person is here, with the authorization request at hand (a link or a login)
          .withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
            @Override
            public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
              filter.setAuthenticationResultConverter(oidcAccounts::authenticate);
              return filter;
            }
          }));
    }
    return http.build();
  }

  /** Only the codes the web app knows (OidcAccounts.ERRORS), anything else is "failed": no provider text in URLs. */
  private static String oidcErrorCode(AuthenticationException exception) {
    if (exception instanceof OAuth2AuthenticationException e && OidcAccounts.ERRORS.contains(e.getError().getErrorCode())) {
      return e.getError().getErrorCode().replace('_', '-');
    }
    return "failed";
  }

  @Bean
  public AuthenticationProvider authenticationProvider(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return provider;
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
    return configuration.getAuthenticationManager();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
