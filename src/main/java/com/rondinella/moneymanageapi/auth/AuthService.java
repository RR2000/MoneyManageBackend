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
    if (userRepository.findByUsername(username).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already taken");
    }
    User user = new User();
    user.setUsername(username);
    user.setPassword(passwordEncoder.encode(request.getPassword()));
    user.setRole("USER");
    user = userRepository.save(user);

    String schema = TenantContext.schemaOf(user.getId());
    tenantSchemas.migrate(schema);
    if (request.isSampleData()) {
      TenantContext.runAs(schema, () -> {
        sampleData.load();
        return null;
      });
    }
    return new AuthResponse(jwtService.generateToken(user.getUsername()));
  }

  public AuthResponse login(LoginRequest request) {
    authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
    return new AuthResponse(jwtService.generateToken(request.getUsername()));
  }
}
