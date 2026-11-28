package com.rondinella.moneymanageapi.demo;

import com.rondinella.moneymanageapi.auth.AppUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a demo sandbox cannot do, refused before the endpoint runs with a code the web app explains (in {@code message}
 * and {@code code}, like the other codes of the API):
 * <ul>
 *   <li>DEMO_NO_BANKING (403): open banking, every /api/banking endpoint;</li>
 *   <li>DEMO_NO_EMAIL (403): mail, the account's email (confirmation link);</li>
 *   <li>DEMO_NO_SSO (403): linking the account to the site's single sign-on;</li>
 *   <li>DEMO_NO_PRICES (403): asking the market for a symbol (Alpha Vantage): the prices of a sandbox come from the
 *   sample data, and from nowhere else (StockPrices);</li>
 *   <li>DEMO_UPLOAD_TOO_LARGE (413, with {@code limitKb}): a bank, broker or prices file over the demo limit.</li>
 * </ul>
 * Everything else (movements, accounts, categories, plans, assets...) works as for any account, inside the sandbox's
 * own schema. Other accounts never pass here. Password change and account deletion have no endpoint in the API: there
 * is nothing to refuse (a password reset needs a confirmed email, which a sandbox never has).
 */
public class DemoGuard extends OncePerRequestFilter {

  public static final String NO_BANKING = "DEMO_NO_BANKING";
  public static final String NO_EMAIL = "DEMO_NO_EMAIL";
  public static final String NO_SSO = "DEMO_NO_SSO";
  public static final String NO_PRICES = "DEMO_NO_PRICES";
  public static final String UPLOAD_TOO_LARGE = "DEMO_UPLOAD_TOO_LARGE";

  private record Rule(HttpMethod method, String pattern, String code) {
  }

  /** method null: any method */
  private static final List<Rule> BLOCKED = List.of(
      new Rule(null, "/api/banking/**", NO_BANKING),
      new Rule(HttpMethod.POST, "/api/account/email", NO_EMAIL),
      new Rule(null, "/api/account/oidc-link", NO_SSO),
      new Rule(HttpMethod.GET, "/api/instruments/preview", NO_PRICES),
      new Rule(HttpMethod.PUT, "/api/instruments/*/symbol", NO_PRICES));

  private static final List<String> UPLOADS = List.of(
      "/api/banks/transactions/*/upload", "/api/brokers/transactions/upload", "/api/instruments/*/prices");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AntPathMatcher PATHS = new AntPathMatcher();

  private final DemoSettings settings;

  public DemoGuard(DemoSettings settings) {
    this.settings = settings;
  }

  @Override
  protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                  @NonNull FilterChain chain) throws ServletException, IOException {
    if (!isDemo()) {
      chain.doFilter(request, response);
      return;
    }
    String path = request.getRequestURI().substring(request.getContextPath().length());
    for (Rule rule : BLOCKED) {
      if ((rule.method() == null || rule.method().matches(request.getMethod())) && PATHS.match(rule.pattern(), path)) {
        refuse(response, HttpStatus.FORBIDDEN, rule.code(), Map.of());
        return;
      }
    }
    if (HttpMethod.POST.matches(request.getMethod()) && UPLOADS.stream().anyMatch(p -> PATHS.match(p, path))) {
      // the browser always says how long the file is; a body without a length is not taken either
      long length = request.getContentLengthLong();
      if (length < 0 || length > settings.maxUploadBytes()) {
        refuse(response, HttpStatus.CONTENT_TOO_LARGE, UPLOAD_TOO_LARGE, Map.of("limitKb", settings.maxUploadKb()));
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private static boolean isDemo() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof AppUserDetails user && user.isDemo();
  }

  private static void refuse(HttpServletResponse response, HttpStatus status, String code, Map<String, Object> extra)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    JSON.writeValue(response.getOutputStream(), body(status, code, extra));
  }

  /** Same body as the other errors of the API, the code in {@code message} and in {@code code}. */
  static Map<String, Object> body(HttpStatus status, String code, Map<String, Object> extra) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", status.value());
    body.put("error", status.getReasonPhrase());
    body.put("message", code);
    body.put("code", code);
    body.putAll(extra);
    return body;
  }
}
