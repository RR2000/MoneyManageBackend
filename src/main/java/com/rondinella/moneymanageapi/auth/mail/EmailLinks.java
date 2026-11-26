package com.rondinella.moneymanageapi.auth.mail;

import com.rondinella.moneymanageapi.auth.LoginThrottle;
import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Links sent by email: "forgot password" and the confirmation of the account's email (the address a reset is sent
 * to). The standard flow:
 * <ul>
 *   <li>the request always gets the same answer, whether an account has that email or not;</li>
 *   <li>the link holds 32 random bytes; the database keeps only their SHA-256, with an expiry (30 minutes for a
 *   reset, 24 hours for a confirmation) and the moment it was used: it works once;</li>
 *   <li>a new password makes every other reset link of the account useless;</li>
 *   <li>limits per client address, per email and for the whole site ({@link MailLimits});</li>
 *   <li>links point to the web app ({@code app.web-url}), never to the Host of the request.</li>
 * </ul>
 * Emails and tokens never go to the logs.
 */
@Service
public class EmailLinks {

  public static final Duration RESET_TTL = Duration.ofMinutes(30);
  public static final Duration VERIFY_TTL = Duration.ofHours(24);
  private static final int MIN_PASSWORD = 8;
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+");

  private static final Logger log = LoggerFactory.getLogger(EmailLinks.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final MailSettings settings;
  private final Mailer mailer;
  private final ExecutorService mailExecutor;
  private final MailLimits limits;
  private final EmailTokenRepository tokens;
  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final LoginThrottle loginThrottle;
  private final Clock clock;

  @Autowired
  public EmailLinks(MailSettings settings, Mailer mailer, @Qualifier("mailExecutor") ExecutorService mailExecutor,
                    MailLimits limits, EmailTokenRepository tokens, UserRepository users,
                    PasswordEncoder passwordEncoder, LoginThrottle loginThrottle) {
    this(settings, mailer, mailExecutor, limits, tokens, users, passwordEncoder, loginThrottle, Clock.systemUTC());
  }

  EmailLinks(MailSettings settings, Mailer mailer, ExecutorService mailExecutor, MailLimits limits,
             EmailTokenRepository tokens, UserRepository users, PasswordEncoder passwordEncoder,
             LoginThrottle loginThrottle, Clock clock) {
    this.settings = settings;
    this.mailer = mailer;
    this.mailExecutor = mailExecutor;
    this.limits = limits;
    this.tokens = tokens;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.loginThrottle = loginThrottle;
    this.clock = clock;
  }

  public boolean enabled() {
    return settings.enabled();
  }

  /** "Forgot password": a link to the account with this email, if there is one. Same answer either way. */
  @Transactional
  public void requestReset(String rawEmail, String clientIp) {
    requireEnabled();
    String email = normalize(rawEmail);
    if (!validEmail(email)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required");
    }
    checkIp(clientIp);
    Optional<User> user = users.findByEmail(email);
    if (user.isEmpty() || !limits.allowMail(email)) {
      return;
    }
    String token = newToken(user.get(), EmailToken.Purpose.RESET, email, RESET_TTL);
    String link = settings.web() + "/reset-password#token=" + token;
    send(email, "MoneyManage - reimposta la password (reset your password)", """
        Ciao,

        è stato chiesto di reimpostare la password dell'account MoneyManage "%1$s".
        Per sceglierne una nuova apri questo link entro 30 minuti:

        %2$s

        Il link vale una volta sola. Se non sei stato tu, ignora questa email: la password resta quella di prima.

        ---

        Hi,

        someone asked to reset the password of the MoneyManage account "%1$s".
        To choose a new one, open this link within 30 minutes:

        %2$s

        The link works only once. If it wasn't you, ignore this email: your password stays the same.

        --
        MoneyManage, %3$s
        Messaggio automatico, le risposte non vengono lette. Automatic message, replies are not read.
        """.formatted(user.get().getUsername(), link, settings.web()));
  }

  /** Sets a new password with a link from {@link #requestReset}; 400 if the link is unknown, used or expired. */
  @Transactional
  public void resetPassword(String token, String newPassword) {
    requireEnabled();
    validatePassword(newPassword);
    EmailToken used = use(token, EmailToken.Purpose.RESET);
    User user = users.findById(used.getUserId()).orElseThrow(AuthLinkException::new);
    user.setPassword(passwordEncoder.encode(newPassword));
    users.save(user);
    tokens.useAll(user.getId(), EmailToken.Purpose.RESET, clock.instant());
    // whoever reset the password may have been locked out by wrong attempts
    loginThrottle.loginSucceeded(user.getUsername());
    log.info("Password reset by email for one account");
  }

  /**
   * Sends a confirmation link to the email the user wants on the account. Nothing changes until the link is opened:
   * an address becomes the account's only once its owner has confirmed it (it is also how "Accedi con GitHub" finds
   * the account again).
   */
  @Transactional
  public void requestEmailChange(User user, String rawEmail, String clientIp) {
    requireEnabled();
    String email = normalize(rawEmail);
    if (!validEmail(email)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required");
    }
    checkIp(clientIp);
    if (email.equals(user.getEmail()) || !limits.allowMail(email)) {
      return;
    }
    String token = newToken(user, EmailToken.Purpose.VERIFY, email, VERIFY_TTL);
    String link = settings.web() + "/verify-email#token=" + token;
    send(email, "MoneyManage - conferma l'email (confirm your email)", """
        Ciao,

        questo indirizzo è stato indicato come email dell'account MoneyManage "%1$s".
        Per confermarlo apri questo link entro 24 ore:

        %2$s

        Servirà per reimpostare la password se la dimentichi. Se non sei stato tu, ignora questa email:
        senza conferma l'indirizzo non viene usato.

        ---

        Hi,

        this address was given as the email of the MoneyManage account "%1$s".
        To confirm it, open this link within 24 hours:

        %2$s

        It will be used to reset the password if you forget it. If it wasn't you, ignore this email:
        without confirmation the address is not used.

        --
        MoneyManage, %3$s
        Messaggio automatico, le risposte non vengono lette. Automatic message, replies are not read.
        """.formatted(user.getUsername(), link, settings.web()));
  }

  /** Confirms the email of a link from {@link #requestEmailChange}; 409 if another account has it meanwhile. */
  @Transactional
  public void confirmEmail(String token) {
    requireEnabled();
    EmailToken used = use(token, EmailToken.Purpose.VERIFY);
    User user = users.findById(used.getUserId()).orElseThrow(AuthLinkException::new);
    Optional<User> owner = users.findByEmail(used.getEmail());
    if (owner.isPresent() && !owner.get().getId().equals(user.getId())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This email belongs to another account");
    }
    user.setEmail(used.getEmail());
    users.save(user);
    tokens.useAll(user.getId(), EmailToken.Purpose.VERIFY, clock.instant());
  }

  public static String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }

  public static boolean validEmail(String email) {
    return email.length() <= 254 && EMAIL.matcher(email).matches();
  }

  public static String hash(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private EmailToken use(String token, EmailToken.Purpose purpose) {
    if (token == null || token.isBlank() || token.length() > 100) {
      throw new AuthLinkException();
    }
    String hash = hash(token);
    if (tokens.use(hash, purpose, clock.instant()) != 1) {
      throw new AuthLinkException();
    }
    return tokens.findById(hash).orElseThrow(AuthLinkException::new);
  }

  private String newToken(User user, EmailToken.Purpose purpose, String email, Duration ttl) {
    Instant now = clock.instant();
    tokens.deleteExpiredBefore(now.minus(Duration.ofDays(1)));
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    tokens.save(new EmailToken(hash(token), user.getId(), purpose, email, now, now.plus(ttl)));
    return token;
  }

  private void send(String to, String subject, String text) {
    mailExecutor.execute(() -> {
      try {
        mailer.send(to, subject, text);
      } catch (RuntimeException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
          root = root.getCause();
        }
        log.warn("Mail not sent: {}", root.getClass().getName());
      }
    });
  }

  private void checkIp(String clientIp) {
    if (!limits.allowIp(clientIp)) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, try again later");
    }
  }

  private void requireEnabled() {
    if (!enabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
  }

  private static void validatePassword(String password) {
    if (password == null || password.length() < MIN_PASSWORD) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password: at least " + MIN_PASSWORD + " characters");
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password: at most 72 bytes");
    }
  }

  /** The link is unknown, already used or expired: one answer for the three. */
  static final class AuthLinkException extends ResponseStatusException {
    AuthLinkException() {
      super(HttpStatus.BAD_REQUEST, "This link is not valid or has expired");
    }
  }
}
