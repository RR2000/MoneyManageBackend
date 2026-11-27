package com.rondinella.moneymanageapi.banking;

import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.auth.mail.ClientIp;
import com.rondinella.moneymanageapi.banking.EnableBanking.Psu;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Open banking (Enable Banking), only for the users enabled in {@code app.banking.users}: for everybody else, and
 * when the feature is off, every endpoint answers 404. Errors carry a code in {@code message} (UNKNOWN_ASPSP,
 * MONEY_ACCOUNT_TAKEN, ASPSP_RATE_LIMIT_EXCEEDED...), never a sentence: the web app translates them.
 */
@RestController
@RequestMapping(path = "/api/banking", produces = "application/json")
public class BankingController {

  /** fullHistory: "reconnect and import the whole history" (see BankingService#startLink). */
  public record LinkRequest(String aspsp, String country, Boolean fullHistory) {
  }

  public record ReplaceRequest(String token) {
  }

  public record DeleteOlderRequest(java.time.LocalDate before) {
  }

  public record MoneyAccountRequest(String moneyAccount) {
  }

  private final BankingService banking;
  private final UserRepository users;

  public BankingController(BankingService banking, UserRepository users) {
    this.banking = banking;
    this.users = users;
  }

  @GetMapping("/aspsps")
  public List<BankingService.AspspView> aspsps(@AuthenticationPrincipal UserDetails principal,
                                               @RequestParam(defaultValue = "IT") String country) {
    enabledUser(principal);
    return banking.aspsps(country);
  }

  /** A new link: the answer is the bank's URL, where the user gives the consent; the bank comes back to /callback. */
  @PostMapping("/links")
  public Map<String, String> startLink(@AuthenticationPrincipal UserDetails principal, @RequestBody LinkRequest body,
                                       HttpServletRequest request) {
    User user = enabledUser(principal);
    if (body == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_ASPSP");
    }
    return Map.of("url", banking.startLink(user.getId(), body.aspsp(), body.country(), psu(request),
        Boolean.TRUE.equals(body.fullHistory())));
  }

  /**
   * Where the bank sends the browser back, without a login: the state is the proof. Always a redirect to the web app,
   * /banks?result=ok or /banks?result=error, never a code in the URL.
   */
  @GetMapping("/callback")
  public void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                       @RequestParam(required = false) String error, HttpServletRequest request,
                       HttpServletResponse response) throws IOException {
    boolean ok;
    try {
      ok = banking.callback(code, state, error, psu(request));
    } catch (RuntimeException e) {
      ok = false;
    }
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.sendRedirect("/banks?result=" + (ok ? "ok" : "error"));
  }

  @GetMapping("/links")
  public List<BankingService.LinkView> links(@AuthenticationPrincipal UserDetails principal) {
    enabledUser(principal);
    return banking.links();
  }

  @PutMapping("/accounts/{id}")
  public BankingService.AccountView setMoneyAccount(@AuthenticationPrincipal UserDetails principal,
                                                    @PathVariable long id, @RequestBody MoneyAccountRequest body,
                                                    HttpServletRequest request) {
    enabledUser(principal);
    return banking.setMoneyAccount(id, body == null ? null : body.moneyAccount(), psu(request));
  }

  /** Preview of "replace with the bank's data": reads the bank, changes nothing, answers the counts and a token. */
  @PostMapping("/accounts/{id}/replace/preview")
  public BankingService.Replace replacePreview(@AuthenticationPrincipal UserDetails principal, @PathVariable long id,
                                               HttpServletRequest request) {
    enabledUser(principal);
    return banking.replacePreview(id, psu(request));
  }

  /** "Replace with the bank's data", confirmed with the token of the preview. */
  @PostMapping("/accounts/{id}/replace")
  public BankingService.Replace replace(@AuthenticationPrincipal UserDetails principal, @PathVariable long id,
                                        @RequestBody ReplaceRequest body) {
    enabledUser(principal);
    return banking.replace(id, body == null ? null : body.token());
  }

  /** Deletes the rows from a file or typed in older than the bank's history ({@code before}, as the user saw it). */
  @PostMapping("/accounts/{id}/delete-older")
  public BankingService.DeletedOlder deleteOlder(@AuthenticationPrincipal UserDetails principal, @PathVariable long id,
                                                 @RequestBody DeleteOlderRequest body) {
    enabledUser(principal);
    return banking.deleteOlder(id, body == null ? null : body.before());
  }

  @PostMapping("/sync")
  public BankingService.SyncResult sync(@AuthenticationPrincipal UserDetails principal, HttpServletRequest request) {
    enabledUser(principal);
    return banking.syncNow(psu(request));
  }

  @DeleteMapping("/links/{id}")
  public ResponseEntity<Void> deleteLink(@AuthenticationPrincipal UserDetails principal, @PathVariable long id,
                                         HttpServletRequest request) {
    enabledUser(principal);
    banking.deleteLink(id, psu(request));
    return ResponseEntity.noContent().build();
  }

  private User enabledUser(UserDetails principal) {
    if (principal == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    User user = users.findByUsername(principal.getUsername())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    if (!banking.enabledFor(user.getId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    return user;
  }

  private static Psu psu(HttpServletRequest request) {
    String agent = request.getHeader("User-Agent");
    return new Psu(ClientIp.of(request), agent == null || agent.length() > 500 ? null : agent);
  }
}
