package com.rondinella.moneymanageapi.banking;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The settings: the key in base64 on one line (PKCS#8 or PKCS#1), the enabled users, off when something is missing. */
class BankingConfigTests {

  private static String b64Pem(String type, byte[] der) {
    String pem = "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
        + "\n-----END " + type + "-----\n";
    return Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void readsAPkcs8AndAPkcs1Key() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    KeyPair keys = g.generateKeyPair();
    byte[] pkcs8 = keys.getPrivate().getEncoded();
    PrivateKey fromPkcs8 = BankingConfig.privateKey(b64Pem("PRIVATE KEY", pkcs8));
    assertArrayEquals(pkcs8, fromPkcs8.getEncoded());

    // the PKCS#1 key is the OCTET STRING at the end of the PKCS#8 one (26 bytes of header for a 2048-bit key)
    byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
    assertEquals(0x30, pkcs1[0] & 0xff);
    assertArrayEquals(pkcs8, BankingConfig.pkcs1ToPkcs8(pkcs1));
    assertArrayEquals(pkcs8, BankingConfig.privateKey(b64Pem("RSA PRIVATE KEY", pkcs1)).getEncoded());
  }

  @Test
  void offUnlessEverythingIsThere() throws Exception {
    BankingConfig config = new BankingConfig();
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    String key = b64Pem("PRIVATE KEY", g.generateKeyPair().getPrivate().getEncoded());

    BankingSettings on = config.bankingSettings("app-1", key, "7, 12", "https://api.example.org/", "https://money.example.org/");
    assertTrue(on.enabled());
    assertEquals(Set.of(7L, 12L), on.users());
    assertTrue(on.enabledFor(12L));
    assertFalse(on.enabledFor(8L));
    assertEquals("https://money.example.org/api/banking/callback", on.redirectUrl());
    assertEquals("https://api.example.org", on.apiUrl());
    assertFalse(on.toString().contains(key));

    assertFalse(config.bankingSettings("", key, "7", "https://api.example.org", "https://m.example.org").enabled());
    assertFalse(config.bankingSettings("app-1", "", "7", "https://api.example.org", "https://m.example.org").enabled());
    assertFalse(config.bankingSettings("app-1", "bm90IGEga2V5", "7", "https://api.example.org", "https://m.example.org").enabled());
    assertFalse(config.bankingSettings("app-1", key, "", "https://api.example.org", "https://m.example.org").enabled());
    assertFalse(config.bankingSettings("app-1", key, "7", "https://api.example.org", "").enabled());
    assertFalse(config.bankingSettings("app-1", key, "abc", "https://api.example.org", "https://m.example.org").enabled());
  }
}
