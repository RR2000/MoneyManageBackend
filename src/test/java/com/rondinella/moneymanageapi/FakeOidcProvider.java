package com.rondinella.moneymanageapi;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny OpenID Connect provider for the tests, on a local port: discovery, keys, token endpoint, userinfo. The test
 * plays the browser: it reads the authorization request the backend sends to {@link #authorizationEndpoint()},
 * chooses who logs in ({@link #issueCode}), then calls the backend's callback with the code. The token endpoint
 * accepts only a public client with PKCE: no secret, code_verifier matching the S256 challenge, same redirect URI.
 */
final class FakeOidcProvider {

  static final String CLIENT_ID = "money-test-client";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What the provider knows of a code it issued. */
  private record Grant(String challenge, String redirectUri, String nonce, Map<String, Object> claims) {
  }

  private final HttpServer server;
  private final RSAKey key;
  private final Map<String, Grant> codes = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> accessTokens = new ConcurrentHashMap<>();
  /** The token requests seen, for the assertions (form fields, and the Authorization header if any). */
  final List<Map<String, String>> tokenRequests = new java.util.concurrent.CopyOnWriteArrayList<>();

  private FakeOidcProvider() {
    try {
      key = new RSAKeyGenerator(2048).keyID("test-key").generate();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/.well-known/openid-configuration", ex -> json(ex, 200, discovery()));
    server.createContext("/keys", ex -> json(ex, 200, new JWKSet(key.toPublicJWK()).toJSONObject()));
    server.createContext("/oauth/v2/token", this::token);
    server.createContext("/oidc/v1/userinfo", this::userInfo);
    server.start();
  }

  private static FakeOidcProvider instance;

  /** One provider per JVM, like the embedded database. */
  static synchronized FakeOidcProvider get() {
    if (instance == null) {
      instance = new FakeOidcProvider();
    }
    return instance;
  }

  String issuer() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  String authorizationEndpoint() {
    return issuer() + "/oauth/v2/authorize";
  }

  String endSessionEndpoint() {
    return issuer() + "/oidc/v1/end_session";
  }

  private Map<String, Object> discovery() {
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("issuer", issuer());
    d.put("authorization_endpoint", authorizationEndpoint());
    d.put("token_endpoint", issuer() + "/oauth/v2/token");
    d.put("userinfo_endpoint", issuer() + "/oidc/v1/userinfo");
    d.put("jwks_uri", issuer() + "/keys");
    d.put("end_session_endpoint", endSessionEndpoint());
    d.put("response_types_supported", List.of("code"));
    d.put("subject_types_supported", List.of("public"));
    d.put("id_token_signing_alg_values_supported", List.of("RS256"));
    d.put("token_endpoint_auth_methods_supported", List.of("none", "client_secret_basic"));
    d.put("code_challenge_methods_supported", List.of("S256"));
    d.put("scopes_supported", List.of("openid", "profile", "email"));
    return d;
  }

  /**
   * The user logs in at the provider: the authorization request (query of the redirect the backend sent) must be a
   * code request with PKCE S256 for this client; answers the code for the callback.
   */
  String issueCode(Map<String, String> authorizationRequest, Map<String, Object> userClaims) {
    if (!"code".equals(authorizationRequest.get("response_type"))
        || !CLIENT_ID.equals(authorizationRequest.get("client_id"))
        || !"S256".equals(authorizationRequest.get("code_challenge_method"))
        || authorizationRequest.get("code_challenge") == null
        || !authorizationRequest.getOrDefault("scope", "").contains("openid")) {
      throw new AssertionError("not a PKCE code request for the client: " + authorizationRequest.keySet());
    }
    String code = UUID.randomUUID().toString();
    codes.put(code, new Grant(authorizationRequest.get("code_challenge"), authorizationRequest.get("redirect_uri"),
        authorizationRequest.get("nonce"), userClaims));
    return code;
  }

  private void token(HttpExchange ex) throws IOException {
    Map<String, String> form = parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    Map<String, String> seen = new HashMap<>(form);
    String authorization = ex.getRequestHeaders().getFirst("Authorization");
    if (authorization != null) {
      seen.put("Authorization", authorization);
    }
    tokenRequests.add(seen);
    Grant grant = codes.remove(form.getOrDefault("code", ""));
    if (grant == null
        || !"authorization_code".equals(form.get("grant_type"))
        || !CLIENT_ID.equals(form.get("client_id"))
        || form.containsKey("client_secret") || authorization != null
        || !grant.redirectUri().equals(form.get("redirect_uri"))
        || !grant.challenge().equals(s256(form.getOrDefault("code_verifier", "")))) {
      json(ex, 400, Map.of("error", "invalid_grant"));
      return;
    }
    Instant now = Instant.now();
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
        .issuer(issuer())
        .audience(CLIENT_ID)
        .claim("azp", CLIENT_ID)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(600)));
    if (grant.nonce() != null) {
      claims.claim("nonce", grant.nonce());
    }
    grant.claims().forEach(claims::claim);
    String idToken;
    try {
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
          claims.build());
      jwt.sign(new RSASSASigner(key));
      idToken = jwt.serialize();
    } catch (JOSEException e) {
      throw new IOException(e);
    }
    String accessToken = UUID.randomUUID().toString();
    accessTokens.put(accessToken, grant.claims());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("access_token", accessToken);
    body.put("token_type", "Bearer");
    body.put("expires_in", 600);
    body.put("scope", "openid profile email");
    body.put("id_token", idToken);
    json(ex, 200, body);
  }

  private void userInfo(HttpExchange ex) throws IOException {
    String authorization = ex.getRequestHeaders().getFirst("Authorization");
    Map<String, Object> claims = authorization == null ? null
        : accessTokens.get(authorization.replaceFirst("(?i)^Bearer ", ""));
    if (claims == null) {
      json(ex, 401, Map.of("error", "invalid_token"));
      return;
    }
    json(ex, 200, claims);
  }

  static Map<String, String> parse(String query) {
    Map<String, String> out = new HashMap<>();
    if (query == null || query.isEmpty()) {
      return out;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
      String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      out.put(k, v);
    }
    return out;
  }

  private static String s256(String verifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void json(HttpExchange ex, int status, Object body) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }
}
