package com.rondinella.moneymanageapi.auth.mail;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * SMTP from the environment (app.mail.*, see application.properties). The sender is kept inside the {@link Mailer}
 * on purpose: a JavaMailSender bean would turn on Spring's mail health check, and the site would look down whenever
 * the relay is slow.
 */
@Configuration(proxyBeanMethods = false)
public class MailConfig {

  private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

  @Bean
  MailSettings mailSettings(@Value("${app.mail.host:}") String host,
                            @Value("${app.mail.port:587}") int port,
                            @Value("${app.mail.username:}") String username,
                            @Value("${app.mail.password:}") String password,
                            @Value("${app.mail.from:}") String from,
                            @Value("${app.mail.from-name:MoneyManage}") String fromName,
                            @Value("${app.web-url:}") String webUrl) {
    MailSettings settings = new MailSettings(host, port, username, password, from, fromName, webUrl);
    log.info("Password reset by email: {}", settings.enabled() ? "on" : "off");
    return settings;
  }

  @Bean
  Mailer mailer(MailSettings settings) {
    if (!settings.enabled()) {
      return (to, subject, text) -> {
        throw new IllegalStateException("mail is not configured");
      };
    }
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(settings.host());
    sender.setPort(settings.port());
    sender.setUsername(settings.username());
    sender.setPassword(settings.password());
    sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
    Properties props = sender.getJavaMailProperties();
    props.put("mail.transport.protocol", "smtp");
    props.put("mail.smtp.auth", "true");
    // STARTTLS on port 587, never in clear
    props.put("mail.smtp.starttls.enable", "true");
    props.put("mail.smtp.starttls.required", "true");
    props.put("mail.smtp.ssl.checkserveridentity", "true");
    props.put("mail.smtp.connectiontimeout", "10000");
    props.put("mail.smtp.timeout", "10000");
    props.put("mail.smtp.writetimeout", "10000");
    return (to, subject, text) -> {
      try {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        helper.setFrom(settings.from(), settings.fromName());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(text, false);
        sender.send(message);
      } catch (Exception e) {
        throw new IllegalStateException("mail not sent", e);
      }
    };
  }

  /**
   * Mails leave in the background: the answer to "forgot password" takes the same time whether the account exists
   * or not. One thread, at most 100 waiting; beyond that they are dropped (the limits make it unlikely).
   */
  @Bean(destroyMethod = "shutdown")
  ExecutorService mailExecutor() {
    return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
        r -> {
          Thread t = new Thread(r, "mail");
          t.setDaemon(true);
          return t;
        },
        (r, executor) -> log.warn("Mail queue full: one mail dropped"));
  }
}
