package com.rondinella.moneymanageapi.common.configurations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Closed by default: the web app and the API share the origin (in production behind the same nginx, locally through
 * the Angular dev server proxy), so no other site may call the API from a browser. MONEY_CORS_ORIGINS opens it to
 * a list of origins, if ever needed.
 */
@Configuration
public class CorsConfig {

  @Value("${app.cors.allowed-origins:}")
  private String[] allowedOrigins;

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    List<String> origins = Arrays.stream(allowedOrigins == null ? new String[0] : allowedOrigins)
        .map(String::trim)
        .filter(o -> !o.isEmpty() && !o.equals("*"))
        .toList();
    if (!origins.isEmpty()) {
      CorsConfiguration configuration = new CorsConfiguration();
      configuration.setAllowedOrigins(origins);
      configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
      configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
      // the token travels in the Authorization header, never in a cookie
      configuration.setAllowCredentials(false);
      source.registerCorsConfiguration("/api/**", configuration);
    }
    return source;
  }
}
