package com.rondinella.moneymanageapi.auth.mail;

/** Sends one plain text email. The tests replace it with a mock: no real mail leaves a test. */
public interface Mailer {

  void send(String to, String subject, String text);
}
