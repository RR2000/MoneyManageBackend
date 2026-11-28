package com.rondinella.moneymanageapi;

import com.rondinella.moneymanageapi.auth.JwtService;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionDto;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionService;
import com.rondinella.moneymanageapi.tenant.TenantContext;
import org.springframework.boot.test.context.TestComponent;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Movements of the source MANUAL in the schema of a logged-in user, through the service as the sample data do: the API
 * has no endpoint to add them one by one. The JSON is the one of {@link BankTransactionDto}. Made-up data only.
 */
@TestComponent
class ManualMovements {

  private final BankTransactionService service;
  private final JwtService jwt;
  private final UserRepository users;
  private final JsonMapper json;

  ManualMovements(BankTransactionService service, JwtService jwt, UserRepository users, JsonMapper json) {
    this.service = service;
    this.jwt = jwt;
    this.users = users;
    this.json = json;
  }

  /** {@code auth}: "Bearer <token>"; {@code movements}: a JSON array of movements */
  List<BankTransactionDto> add(String auth, String movements) {
    String schema = schemaOf(auth);
    List<BankTransactionDto> rows = json.readValue(movements, new TypeReference<List<BankTransactionDto>>() {
    });
    return TenantContext.runAs(schema, () -> service.addTransactions(rows));
  }

  /** the schema of the user of {@code auth} ("Bearer <token>") */
  String schemaOf(String auth) {
    String username = jwt.extractUsername(auth.substring("Bearer ".length()));
    return TenantContext.schemaOf(users.findByUsername(username).orElseThrow().getId());
  }
}
