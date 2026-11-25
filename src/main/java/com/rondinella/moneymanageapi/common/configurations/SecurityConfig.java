package com.rondinella.moneymanageapi.common.configurations;

import com.rondinella.moneymanageapi.auth.AuthService;
import com.rondinella.moneymanageapi.auth.github.GitHubLogin;
import com.rondinella.moneymanageapi.auth.github.GitHubUserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
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
                                                 AuthService authService) throws Exception {
    http
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        // Stateless token API: CSRF tokens are not applicable to the JSON
        // endpoints, which are authenticated solely via the Authorization
        // header (no cookies/session). CSRF stays active for any other path.
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/auth/**").permitAll()
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            .requestMatchers("/actuator/health/**").permitAll()
            .requestMatchers("/error").permitAll()
            .anyRequest().authenticated())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // no token or an expired one: 401, the web app goes back to the login page
        .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
        .authenticationProvider(authenticationProvider)
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
    if (gitHub.enabled()) {
      // GitHub sends the browser back to the backend, which gives the web app its token in the URL fragment
      // (never sent to a server, so it does not end up in access logs)
      http.oauth2Login(oauth2 -> oauth2
          .loginPage("/login")
          .clientRegistrationRepository(new InMemoryClientRegistrationRepository(gitHub.registration()))
          .authorizationEndpoint(a -> a.baseUri(GitHubLogin.AUTHORIZATION_BASE))
          .redirectionEndpoint(r -> r.baseUri(GitHubLogin.CALLBACK_BASE + "/*"))
          .userInfoEndpoint(u -> u.userService(gitHubUsers))
          .successHandler((request, response, authentication) -> {
            String username = ((OAuth2User) authentication.getPrincipal()).getName();
            response.sendRedirect("/login#token=" + authService.tokenFor(username));
          })
          .failureHandler((request, response, exception) -> response.sendRedirect("/login#github-error")));
    }
    return http.build();
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
