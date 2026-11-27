package com.rondinella.moneymanageapi.banking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

/** app.banking.* (see application.properties). A key that cannot be read turns the feature off, it never stops the app. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class BankingConfig {

  private static final Logger log = LoggerFactory.getLogger(BankingConfig.class);

  @Bean
  BankingSettings bankingSettings(@Value("${app.banking.app-id:}") String appId,
                                  @Value("${app.banking.private-key-b64:}") String keyB64,
                                  @Value("${app.banking.users:}") String users,
                                  @Value("${app.banking.api-url:https://api.enablebanking.com}") String apiUrl,
                                  @Value("${app.web-url:}") String webUrl) {
    PrivateKey key = null;
    if (keyB64 != null && !keyB64.isBlank()) {
      try {
        key = privateKey(keyB64);
      } catch (RuntimeException | GeneralSecurityException e) {
        // never the key or the exception message
        log.warn("Open banking: the private key cannot be read ({})", e.getClass().getSimpleName());
      }
    }
    BankingSettings settings = new BankingSettings(appId == null ? "" : appId.trim(), key, userIds(users),
        apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl, webUrl);
    log.info("Open banking: {}", settings.enabled() ? "on for " + settings.users().size() + " user(s)" : "off");
    return settings;
  }

  static Set<Long> userIds(String users) {
    Set<Long> ids = new LinkedHashSet<>();
    if (users == null) {
      return ids;
    }
    for (String part : users.split("[,;\\s]+")) {
      if (!part.isBlank()) {
        try {
          ids.add(Long.parseLong(part.trim()));
        } catch (NumberFormatException e) {
          log.warn("Open banking: an entry of app.banking.users is not a user id, ignored");
        }
      }
    }
    return Set.copyOf(ids);
  }

  /**
   * The RSA private key of the Enable Banking application: its PEM file ("BEGIN PRIVATE KEY", PKCS#8, as the control
   * panel gives it, or "BEGIN RSA PRIVATE KEY", PKCS#1) encoded in base64 on one line, so it fits an environment
   * variable.
   */
  static PrivateKey privateKey(String b64) throws GeneralSecurityException {
    String pem = new String(Base64.getMimeDecoder().decode(b64.trim()), StandardCharsets.US_ASCII);
    boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
    String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("[\\s]+", "");
    byte[] der = Base64.getDecoder().decode(body);
    if (pkcs1) {
      der = pkcs1ToPkcs8(der);
    }
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  /** PKCS#8 = SEQUENCE { INTEGER 0, SEQUENCE { rsaEncryption, NULL }, OCTET STRING { the PKCS#1 key } }. */
  static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
    byte[] version = {0x02, 0x01, 0x00};
    byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01,
        0x01, 0x05, 0x00};
    byte[] octets = der(0x04, pkcs1);
    byte[] content = new byte[version.length + algorithm.length + octets.length];
    System.arraycopy(version, 0, content, 0, version.length);
    System.arraycopy(algorithm, 0, content, version.length, algorithm.length);
    System.arraycopy(octets, 0, content, version.length + algorithm.length, octets.length);
    return der(0x30, content);
  }

  private static byte[] der(int tag, byte[] content) {
    int n = content.length;
    byte[] length;
    if (n < 0x80) {
      length = new byte[] {(byte) n};
    } else if (n < 0x100) {
      length = new byte[] {(byte) 0x81, (byte) n};
    } else if (n < 0x10000) {
      length = new byte[] {(byte) 0x82, (byte) (n >> 8), (byte) n};
    } else {
      length = new byte[] {(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
    }
    byte[] out = Arrays.copyOf(new byte[] {(byte) tag}, 1 + length.length + n);
    System.arraycopy(length, 0, out, 1, length.length);
    System.arraycopy(content, 0, out, 1 + length.length, n);
    return out;
  }
}
