package com.rondinella.moneymanageapi.auth;

import com.rondinella.moneymanageapi.auth.dto.AuthResponse;
import com.rondinella.moneymanageapi.auth.dto.LoginRequest;
import com.rondinella.moneymanageapi.auth.dto.RegisterRequest;
import com.rondinella.moneymanageapi.demo.SampleData;
import com.rondinella.moneymanageapi.tenant.TenantContext;
import com.rondinella.moneymanageapi.tenant.TenantSchemas;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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

  public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
                     AuthenticationManager authenticationManager, TenantSchemas tenantSchemas, SampleData sampleData) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.authenticationManager = authenticationManager;
    this.tenantSchemas = tenantSchemas;
    this.sampleData = sampleData;
  }

  public AuthResponse register(RegisterRequest request) {
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
    if (userRepository.findByUsername(username).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
    }
    User user = createAccount(username, request.getPassword(), null);
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

  public AuthResponse login(LoginRequest request) {
    authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
    return new AuthResponse(jwtService.generateToken(request.getUsername()));
  }
}
