package com.rondinella.moneymanageapi.auth;

import com.rondinella.moneymanageapi.auth.dto.AuthResponse;
import com.rondinella.moneymanageapi.auth.dto.LoginRequest;
import com.rondinella.moneymanageapi.auth.dto.RegisterRequest;
import com.rondinella.moneymanageapi.auth.mail.EmailLinks;
import com.rondinella.moneymanageapi.demo.SampleData;
import com.rondinella.moneymanageapi.tenant.TenantContext;
import com.rondinella.moneymanageapi.tenant.TenantSchemas;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuthService {

  private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,32}");
  private static final int MIN_PASSWORD = 8;

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AuthenticationManager authenticationManager;
  private final TenantSchemas tenantSchemas;
  private final SampleData sampleData;
  private final LoginThrottle throttle;
  private final EmailLinks emailLinks;

  public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                     AuthenticationManager authenticationManager, TenantSchemas tenantSchemas, SampleData sampleData,
                     LoginThrottle throttle, EmailLinks emailLinks) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.authenticationManager = authenticationManager;
    this.tenantSchemas = tenantSchemas;
    this.sampleData = sampleData;
    this.throttle = throttle;
    this.emailLinks = emailLinks;
  }

  public AuthResponse register(RegisterRequest request, String clientIp) {
    String username = request.getUsername() == null ? "" : request.getUsername().trim();
    if (!USERNAME.matcher(username).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Username: 3-32 characters, letters, digits, dot, dash or underscore");
    }
    if (request.getPassword() == null || request.getPassword().length() < MIN_PASSWORD) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password: at least " + MIN_PASSWORD + " characters");
    }
    // BCrypt uses at most 72 bytes
    if (request.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password: at most 72 bytes");
    }
    EmailLinks links = emailLinks;
    String email = links.enabled() ? EmailLinks.normalize(request.getEmail()) : "";
    if (!email.isEmpty() && !EmailLinks.validEmail(email)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email: not a valid address");
    }
    // "Mario" and "mario" would be two accounts the login could not tell apart
    if (userRepository.existsByUsernameIgnoreCase(username)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
    }
    throttle.register();
    User user = createAccount(username, request.getPassword(), null);
    if (!email.isEmpty()) {
      // the email is not the account's until its owner opens the link; a refused request does not stop the registration
      try {
        links.requestEmailChange(user, email, clientIp);
      } catch (ResponseStatusException e) {
        // too many requests from this address: the email can be added later from the account page
      }
    }
    if (request.isSampleData()) {
      TenantContext.runAs(TenantContext.schemaOf(user.getId()), () -> {
        sampleData.load();
        return null;
      });
    }
    return new AuthResponse(jwtService.generateToken(user.getUsername()));
  }

  /** A new account with its own (empty) schema. Without a password (GitHub accounts) nobody can log in with one. */
  public User createAccount(String username, String password, String email) {
    User user = new User();
    user.setUsername(username);
    user.setPassword(passwordEncoder.encode(password != null ? password : UUID.randomUUID().toString()));
    user.setRole("USER");
    user.setEmail(email);
    user = userRepository.save(user);
    tenantSchemas.migrate(TenantContext.schemaOf(user.getId()));
    return user;
  }

  /** Token for an account that already proved who it is (GitHub). */
  public String tokenFor(String username) {
    return jwtService.generateToken(username);
  }

  /**
   * Login with the username (any case) or with the account's confirmed email. Every failure gets the same answer,
   * and the brake counts per account: username and email of one account share the same 5 attempts.
   */
  public AuthResponse login(LoginRequest request) {
    String given = request.getUsername() == null ? "" : request.getUsername().trim();
    String username = accountUsername(given).orElse(given);
    throttle.checkLogin(username);
    try {
      authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, request.getPassword()));
    } catch (BadCredentialsException e) {
      // also for a username that does not exist (Spring answers the same way, in the same time)
      throttle.loginFailed(username);
      throw e;
    }
    throttle.loginSucceeded(username);
    return new AuthResponse(jwtService.generateToken(username));
  }

  /** The username of the account that what was typed in the login form stands for, if any. */
  Optional<String> accountUsername(String given) {
    if (given.isEmpty()) {
      return Optional.empty();
    }
    if (given.contains("@")) {
      // usernames never contain @: it is an email, accounts have one only once confirmed (or verified by GitHub)
      return userRepository.findByEmail(EmailLinks.normalize(given)).map(User::getUsername);
    }
    Optional<User> exact = userRepository.findByUsername(given);
    if (exact.isPresent()) {
      return exact.map(User::getUsername);
    }
    List<User> sameLetters = userRepository.findAllByUsernameIgnoreCase(given);
    return sameLetters.size() == 1 ? Optional.of(sameLetters.getFirst().getUsername()) : Optional.empty();
  }
}
