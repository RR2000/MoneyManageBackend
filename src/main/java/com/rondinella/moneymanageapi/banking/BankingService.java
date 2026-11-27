package com.rondinella.moneymanageapi.banking;

import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import com.rondinella.moneymanageapi.auth.mail.MailSettings;
import com.rondinella.moneymanageapi.auth.mail.Mailer;
import com.rondinella.moneymanageapi.banking.EnableBanking.Psu;
import com.rondinella.moneymanageapi.banktransactions.BankTransaction;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionRepository;
import com.rondinella.moneymanageapi.banktransactions.BankTransactionService;
import com.rondinella.moneymanageapi.tenant.TenantContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Open banking for the enabled users: links to a bank, their accounts, the syncs of movements and balances. Works in
 * the schema of {@link TenantContext#current()}; the scheduled sync goes through every enabled user with
 * {@link TenantContext#runAs}. Logs say only counts, link ids, error codes and endpoints.
 */
@Service
public class BankingService {

  private static final Logger log = LoggerFactory.getLogger(BankingService.class);

  /** Consent asked when the bank does not say its maximum. */
  static final Duration DEFAULT_CONSENT = Duration.ofDays(90);
  static final Duration RATE_LIMIT_PAUSE = Duration.ofHours(6);
  static final Duration EXPIRY_WARNING = Duration.ofDays(7);
  static final Duration ASPSPS_CACHE = Duration.ofHours(24);
  static final int SYNC_OVERLAP_DAYS = 5;
  private static final int MAX_PAGES = 500;
  /** Balance used for the history, in this order. */
  private static final List<String> BALANCE_TYPES = List.of("CLBD", "ITAV", "XPCD");

  public record AspspView(String name, String country, String logo, Long maximumConsentValidity) {
  }

  public record BalanceView(BigDecimal amount, String currency, String type, LocalDate date) {
  }

  public record AccountView(Long id, String name, String iban, String currency, BalanceView balance,
                            String moneyAccount) {
  }

  public record LinkView(Long id, String aspsp, String country, BankLink.Status status, Instant validUntil,
                         Instant lastSyncAt, String lastError, int lastAmbiguous, List<AccountView> accounts) {
  }

  public record SyncResult(int imported, int matched, int ambiguous, List<LinkView> links) {
  }

  private record Counts(int imported, int matched, int ambiguous) {
    Counts plus(Counts o) {
      return new Counts(imported + o.imported, matched + o.matched, ambiguous + o.ambiguous);
    }
  }

  private record CachedAspsps(Instant at, List<AspspView> aspsps) {
  }

  private final BankingSettings settings;
  private final EnableBanking bank;
  private final BankAuthStates states;
  private final BankLinkRepository links;
  private final BankLinkAccountRepository accounts;
  private final BankTransactionRepository transactions;
  private final BankTransactionService transactionService;
  private final BankImport importer;
  private final UserRepository users;
  private final MailSettings mailSettings;
  private final Mailer mailer;
  private final ExecutorService mailExecutor;
  private final ExecutorService syncExecutor;
  private final Clock clock = Clock.systemUTC();
  private final Map<String, CachedAspsps> aspspCache = new ConcurrentHashMap<>();
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  public BankingService(BankingSettings settings, EnableBanking bank, BankAuthStates states, BankLinkRepository links,
                        BankLinkAccountRepository accounts, BankTransactionRepository transactions,
                        BankTransactionService transactionService, BankImport importer, UserRepository users,
                        MailSettings mailSettings, Mailer mailer,
                        @Qualifier("mailExecutor") ExecutorService mailExecutor) {
    this.settings = settings;
    this.bank = bank;
    this.states = states;
    this.links = links;
    this.accounts = accounts;
    this.transactions = transactions;
    this.transactionService = transactionService;
    this.importer = importer;
    this.users = users;
    this.mailSettings = mailSettings;
    this.mailer = mailer;
    this.mailExecutor = mailExecutor;
    // first imports after a callback or a new money account: one at a time, in the background
    this.syncExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(50), r -> {
      Thread t = new Thread(r, "banking");
      t.setDaemon(true);
      return t;
    });
  }

  @PreDestroy
  void stop() {
    syncExecutor.shutdownNow();
  }

  /** Waits until the first imports queued so far are done (the tests use it). */
  public void waitForImports() throws InterruptedException, java.util.concurrent.ExecutionException {
    syncExecutor.submit(() -> { }).get();
  }

  public boolean enabledFor(Long userId) {
    return settings.enabledFor(userId);
  }

  // ---- banks and new links

  public List<AspspView> aspsps(String country) {
    String c = country(country);
    CachedAspsps cached = aspspCache.get(c);
    if (cached != null && cached.at().plus(ASPSPS_CACHE).isAfter(clock.instant())) {
      return cached.aspsps();
    }
    try {
      List<AspspView> list = bank.aspsps(c).stream()
          .map(a -> new AspspView(a.name(), a.country(), a.logo(), a.maximumConsentValidity()))
          .sorted(Comparator.comparing(AspspView::name, String.CASE_INSENSITIVE_ORDER))
          .toList();
      aspspCache.put(c, new CachedAspsps(clock.instant(), list));
      return list;
    } catch (EnableBankingException e) {
      throw bankError(e);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, EnableBankingException.UNREACHABLE);
    }
  }

  /** POST /auth for one of the banks of the list: the URL where the user gives the consent. */
  public String startLink(long userId, String aspspName, String country, Psu psu) {
    String c = country(country);
    if (aspspName == null || aspspName.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_ASPSP");
    }
    AspspView aspsp = aspsps(c).stream().filter(a -> a.name().equals(aspspName)).findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_ASPSP"));
    Duration consent = aspsp.maximumConsentValidity() != null && aspsp.maximumConsentValidity() > 120
        ? Duration.ofSeconds(aspsp.maximumConsentValidity() - 60) : DEFAULT_CONSENT;
    Instant validUntil = clock.instant().plus(consent);
    String state = states.create(userId, aspsp.name(), c, validUntil);
    try {
      return bank.startAuthorization(aspsp.name(), c, validUntil, state, psu);
    } catch (EnableBankingException e) {
      throw bankError(e);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, EnableBankingException.UNREACHABLE);
    }
  }

  /**
   * The bank sent the browser back: the state says whose it is (once, within 10 minutes), the code becomes a session.
   * Accounts already known from an earlier link of the same bank keep their money account and are imported at once.
   *
   * @return true when the link is there
   */
  public boolean callback(String code, String state, String error, Psu psu) {
    Optional<BankAuthStates.Pending> pending = states.consume(state);
    if (pending.isEmpty()) {
      log.warn("Open banking callback: unknown, used or expired state");
      return false;
    }
    BankAuthStates.Pending p = pending.get();
    if (!settings.enabledFor(p.userId())) {
      return false;
    }
    if (error != null || code == null || code.isBlank() || code.length() > 1000) {
      log.info("Open banking callback: no consent given");
      return false;
    }
    EnableBanking.Session session;
    try {
      session = bank.createSession(code, psu);
    } catch (IOException e) {
      log.warn("Open banking: {}", e.getMessage());
      return false;
    }
    String schema = TenantContext.schemaOf(p.userId());
    BankLink link = TenantContext.runAs(schema, () -> withLock(schema, () -> saveLink(p, session)));
    log.info("Open banking: link {} with {} account(s)", link.getId(), session.accounts().size());
    syncLater(schema, link.getId(), null, psu);
    return true;
  }

  private BankLink saveLink(BankAuthStates.Pending p, EnableBanking.Session session) {
    Instant now = clock.instant();
    BankLink link = new BankLink();
    link.setAspspName(p.aspspName());
    link.setAspspCountry(p.aspspCountry());
    link.setSessionId(session.sessionId());
    link.setStatus(BankLink.Status.ACTIVE);
    link.setValidUntil(session.validUntil() != null ? session.validUntil() : p.validUntil());
    link.setCreatedAt(now);
    link = links.save(link);
    for (EnableBanking.Account a : session.accounts()) {
      BankLinkAccount account = new BankLinkAccount();
      account.setLinkId(link.getId());
      account.setUid(a.uid());
      account.setIdentificationHash(a.identificationHash());
      account.setName(a.name() == null ? null : a.name().length() > 200 ? a.name().substring(0, 200) : a.name());
      account.setIbanMasked(mask(a.iban()));
      account.setCurrency(a.currency());
      if (a.identificationHash() != null) {
        // the same account of an earlier link: it keeps its money account and where the import got to
        for (BankLinkAccount old : accounts.findByIdentificationHash(a.identificationHash())) {
          if (!old.getLinkId().equals(link.getId()) && old.getMoneyAccount() != null) {
            account.setMoneyAccount(old.getMoneyAccount());
            account.setSyncFrom(old.getSyncFrom());
            account.setLastBookingDate(old.getLastBookingDate());
            old.setMoneyAccount(null);
            accounts.save(old);
          }
        }
      }
      accounts.save(account);
    }
    // earlier links of the same bank whose accounts all came back in this one: replaced
    List<String> hashes = session.accounts().stream().map(EnableBanking.Account::identificationHash)
        .filter(Objects::nonNull).toList();
    for (BankLink old : links.findByAspspNameAndAspspCountry(p.aspspName(), p.aspspCountry())) {
      if (old.getId().equals(link.getId())) {
        continue;
      }
      List<BankLinkAccount> oldAccounts = accounts.findByLinkIdOrderByIdAsc(old.getId());
      if (!oldAccounts.isEmpty() && oldAccounts.stream().allMatch(o -> hashes.contains(o.getIdentificationHash()))) {
        closeRemote(old, null);
        accounts.deleteAll(oldAccounts);
        links.delete(old);
      }
    }
    return link;
  }

  // ---- links and accounts

  public List<LinkView> links() {
    List<LinkView> result = new ArrayList<>();
    for (BankLink link : links.findAllByOrderByIdAsc()) {
      expireIfOver(link);
      result.add(view(link));
    }
    return result;
  }

  /**
   * The money account where the movements of a bank account go: an existing one or a new name (it appears with the
   * first movement). The import starts from the day after the last movement already in that money account. null or
   * blank: no money account, no more imports.
   */
  public AccountView setMoneyAccount(long accountId, String moneyAccount, Psu psu) {
    String schema = TenantContext.current();
    return withLock(schema, () -> {
      BankLinkAccount account = accounts.findById(accountId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
      String name = moneyAccount == null || moneyAccount.isBlank() ? null : moneyAccount.trim();
      if (name != null && (name.length() > 100 || name.chars().anyMatch(Character::isISOControl))) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MONEY_ACCOUNT");
      }
      if (Objects.equals(name, account.getMoneyAccount())) {
        return accountView(account);
      }
      if (name != null && accounts.findByMoneyAccount(name).stream().anyMatch(o -> !o.getId().equals(accountId))) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "MONEY_ACCOUNT_TAKEN");
      }
      account.setMoneyAccount(name);
      account.setLastBookingDate(null);
      account.setSyncFrom(null);
      if (name != null) {
        BankTransaction last = transactions.findFirstByAccountOrderByDatetimeDescIdDesc(name);
        account.setSyncFrom(last == null ? null : last.getDatetime().toLocalDateTime().toLocalDate().plusDays(1));
      }
      accounts.save(account);
      if (name != null) {
        syncLater(schema, account.getLinkId(), account.getId(), psu);
      }
      return accountView(account);
    });
  }

  /** Closes the consent at the bank (Enable Banking DELETE /sessions) and forgets the link; the movements stay. */
  public void deleteLink(long linkId, Psu psu) {
    String schema = TenantContext.current();
    withLock(schema, () -> {
      BankLink link = links.findById(linkId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
      closeRemote(link, psu);
      accounts.deleteAll(accounts.findByLinkIdOrderByIdAsc(link.getId()));
      links.delete(link);
      return null;
    });
  }

  private void closeRemote(BankLink link, Psu psu) {
    if (link.getStatus() != BankLink.Status.ACTIVE) {
      return;
    }
    try {
      bank.deleteSession(link.getSessionId(), psu);
    } catch (IOException e) {
      // gone already, or the bank is down: the consent ends by itself at valid_until
      log.warn("Open banking: {}", e.getMessage());
    }
  }

  // ---- syncs

  /** Manual sync of every link, with the headers of the user who is there. */
  public SyncResult syncNow(Psu psu) {
    String schema = TenantContext.current();
    Counts counts = withLock(schema, () -> {
      Counts total = new Counts(0, 0, 0);
      for (BankLink link : links.findAllByOrderByIdAsc()) {
        total = total.plus(syncLink(link, null, psu));
      }
      return total;
    });
    return new SyncResult(counts.imported(), counts.matched(), counts.ambiguous(), links());
  }

  /** Twice a day, without a user: every link of every enabled user, then the mails of consents about to end. */
  @Scheduled(cron = "${app.banking.cron:0 30 6,18 * * *}", zone = "${app.banking.zone:Europe/Rome}")
  public void scheduledSync() {
    if (!settings.enabled()) {
      return;
    }
    for (Long userId : settings.users()) {
      Optional<User> user = users.findById(userId);
      if (user.isEmpty()) {
        continue;
      }
      String schema = TenantContext.schemaOf(userId);
      try {
        TenantContext.runAs(schema, () -> withLock(schema, () -> {
          for (BankLink link : links.findAllByOrderByIdAsc()) {
            syncLink(link, null, null);
          }
          expiryMails(user.get());
          return null;
        }));
      } catch (RuntimeException e) {
        log.error("Open banking: scheduled sync failed for a user ({})", e.getClass().getName());
      }
    }
  }

  private void syncLater(String schema, Long linkId, Long onlyAccountId, Psu psu) {
    try {
      syncExecutor.execute(() -> {
        try {
          TenantContext.runAs(schema, () -> withLock(schema, () -> {
            links.findById(linkId).ifPresent(link -> syncLink(link, onlyAccountId, psu));
            return null;
          }));
        } catch (RuntimeException e) {
          log.error("Open banking: first import failed ({})", e.getClass().getName());
        }
      });
    } catch (RuntimeException e) {
      log.warn("Open banking: sync queue full, the next scheduled sync will do it");
    }
  }

  /** One link, in the current schema, under the user's lock. Errors end up in the link, not in an exception. */
  private Counts syncLink(BankLink link, Long onlyAccountId, Psu psu) {
    Instant now = clock.instant();
    Counts total = new Counts(0, 0, 0);
    if (expireIfOver(link) || link.getStatus() != BankLink.Status.ACTIVE) {
      return total;
    }
    if (link.getRetryAfter() != null && link.getRetryAfter().isAfter(now)) {
      return total;
    }
    String error = null;
    boolean stop = false;
    for (BankLinkAccount account : accounts.findByLinkIdOrderByIdAsc(link.getId())) {
      if (stop) {
        break;
      }
      if (onlyAccountId != null && !onlyAccountId.equals(account.getId())) {
        continue;
      }
      try {
        if (account.getMoneyAccount() == null) {
          // not imported yet: only the balance, when the user is there (it does not count on the bank's daily limit)
          if (psu != null) {
            balance(account, psu);
            accounts.save(account);
          }
          continue;
        }
        total = total.plus(syncAccount(account, psu));
      } catch (EnableBankingException e) {
        log.warn("Open banking: link {}: {}", link.getId(), e.getMessage());
        error = e.code();
        if (e.sessionOver()) {
          link.setStatus("REVOKED_SESSION".equals(e.code()) ? BankLink.Status.REVOKED : BankLink.Status.EXPIRED);
          stop = true;
        } else if (e.rateLimited()) {
          link.setRetryAfter(now.plus(RATE_LIMIT_PAUSE));
          stop = true;
        }
      } catch (IOException | RuntimeException e) {
        log.warn("Open banking: link {}: {}", link.getId(), e.getClass().getName());
        error = "SYNC_FAILED";
      }
    }
    link.setLastError(error);
    if (error == null) {
      link.setLastSyncAt(now);
      link.setRetryAfter(null);
      link.setLastAmbiguous(total.ambiguous());
    }
    links.save(link);
    log.info("Open banking: link {} synced, {} new, {} matched, {} ambiguous", link.getId(), total.imported(),
        total.matched(), total.ambiguous());
    return total;
  }

  private Counts syncAccount(BankLinkAccount account, Psu psu) throws IOException {
    boolean first = account.getLastBookingDate() == null;
    LocalDate from = first ? account.getSyncFrom() : account.getLastBookingDate().minusDays(SYNC_OVERLAP_DAYS);
    if (from != null && account.getSyncFrom() != null && from.isBefore(account.getSyncFrom())) {
      from = account.getSyncFrom();
    }
    if (from != null && from.isAfter(LocalDate.now(clock))) {
      from = LocalDate.now(clock);
    }
    List<EnableBanking.Tx> all = new ArrayList<>();
    String key = null;
    int pages = 0;
    do {
      // the same query with the key of the previous page, until there is none (pages can be empty in between)
      EnableBanking.TxPage page = bank.transactions(account.getUid(), from, first ? "longest" : null, key, psu);
      all.addAll(page.transactions());
      key = page.continuationKey();
    } while (key != null && ++pages < MAX_PAGES);
    BankImport.Result result = importer.importInto(account.getMoneyAccount(), account.getSyncFrom(), all);
    if (result.lastBookingDate() != null && (account.getLastBookingDate() == null
        || result.lastBookingDate().isAfter(account.getLastBookingDate()))) {
      account.setLastBookingDate(result.lastBookingDate());
    }
    EnableBanking.Balance balance = balance(account, psu);
    accounts.save(account);
    if (balance != null) {
      // the history of the balance, back from the bank's balance (amounts in the account's currency, not converted)
      transactionService.computeCumulativeAmount(account.getMoneyAccount(), balance.amount());
    }
    return new Counts(result.imported(), result.matched(), result.ambiguous());
  }

  private EnableBanking.Balance balance(BankLinkAccount account, Psu psu) throws IOException {
    List<EnableBanking.Balance> balances = bank.balances(account.getUid(), psu);
    EnableBanking.Balance chosen = null;
    for (String type : BALANCE_TYPES) {
      chosen = balances.stream().filter(b -> type.equals(b.type())).findFirst().orElse(null);
      if (chosen != null) {
        break;
      }
    }
    if (chosen == null && !balances.isEmpty()) {
      chosen = balances.getFirst();
    }
    if (chosen != null) {
      account.setBalanceAmount(chosen.amount());
      account.setBalanceCurrency(chosen.currency());
      account.setBalanceType(chosen.type() == null || chosen.type().length() > 4 ? null : chosen.type());
      account.setBalanceDate(chosen.date() != null ? chosen.date() : LocalDate.now(clock));
    }
    return chosen;
  }

  /** valid_until passed: the link is over, without asking the bank. */
  private boolean expireIfOver(BankLink link) {
    if (link.getStatus() == BankLink.Status.ACTIVE && link.getValidUntil() != null
        && !link.getValidUntil().isAfter(clock.instant())) {
      link.setStatus(BankLink.Status.EXPIRED);
      link.setLastError("EXPIRED_SESSION");
      links.save(link);
      return true;
    }
    return false;
  }

  // ---- consent about to end

  /** One mail per link, 7 days before the end of the consent, if mail is on and the account has an email. */
  void expiryMails(User user) {
    if (!mailSettings.enabled() || user.getEmail() == null) {
      return;
    }
    Instant now = clock.instant();
    for (BankLink link : links.findAllByOrderByIdAsc()) {
      if (link.getStatus() != BankLink.Status.ACTIVE || link.getExpiryMailSentAt() != null
          || link.getValidUntil() == null || link.getValidUntil().minus(EXPIRY_WARNING).isAfter(now)) {
        continue;
      }
      String day = DateTimeFormatter.ISO_LOCAL_DATE.format(link.getValidUntil().atZone(ZoneId.of("Europe/Rome")));
      String text = """
          Hi,

          the link of Money Manage to %1$s ends on %2$s: after that day no more movements and balances arrive from it.
          To keep them coming, open %3$s/banks and link the bank again (it takes a minute at the bank).

          ---

          Ciao,

          il collegamento di Money Manage a %1$s scade il %2$s: dopo quel giorno non arrivano piu' movimenti e saldi.
          Per continuare apri %3$s/banks e collega di nuovo la banca (un minuto sul sito della banca).

          --
          Money Manage, %3$s
          Automatic message, replies are not read. Messaggio automatico, le risposte non vengono lette.
          """.formatted(link.getAspspName(), day, mailSettings.web());
      String to = user.getEmail();
      try {
        mailExecutor.execute(() -> {
          try {
            mailer.send(to, "Money Manage - your bank link ends soon (il collegamento alla banca scade)", text);
          } catch (RuntimeException e) {
            log.warn("Open banking: expiry mail not sent ({})", e.getClass().getName());
          }
        });
        link.setExpiryMailSentAt(now);
        links.save(link);
      } catch (RuntimeException e) {
        log.warn("Open banking: expiry mail not queued");
      }
    }
  }

  // ---- helpers

  private <T> T withLock(String schema, java.util.function.Supplier<T> work) {
    ReentrantLock lock = locks.computeIfAbsent(schema, s -> new ReentrantLock());
    lock.lock();
    try {
      return work.get();
    } finally {
      lock.unlock();
    }
  }

  private LinkView view(BankLink link) {
    List<AccountView> list = accounts.findByLinkIdOrderByIdAsc(link.getId()).stream().map(BankingService::accountView)
        .toList();
    return new LinkView(link.getId(), link.getAspspName(), link.getAspspCountry(), link.getStatus(),
        link.getValidUntil(), link.getLastSyncAt(), link.getLastError(), link.getLastAmbiguous(), list);
  }

  private static AccountView accountView(BankLinkAccount a) {
    BalanceView balance = a.getBalanceAmount() == null ? null
        : new BalanceView(a.getBalanceAmount(), a.getBalanceCurrency(), a.getBalanceType(), a.getBalanceDate());
    return new AccountView(a.getId(), a.getName(), a.getIbanMasked(), a.getCurrency(), balance, a.getMoneyAccount());
  }

  /** Country code and last 4 characters: enough to tell accounts apart. */
  static String mask(String iban) {
    if (iban == null) {
      return null;
    }
    String compact = iban.replaceAll("\\s", "");
    if (compact.length() < 8) {
      return null;
    }
    return compact.substring(0, 2) + " **** " + compact.substring(compact.length() - 4);
  }

  private static String country(String country) {
    if (country == null || !country.matches("[A-Za-z]{2}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_COUNTRY");
    }
    return country.toUpperCase(java.util.Locale.ROOT);
  }

  private static ResponseStatusException bankError(EnableBankingException e) {
    log.warn("Open banking: {}", e.getMessage());
    HttpStatus status = e.rateLimited() || e.status() == 429 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_GATEWAY;
    return new ResponseStatusException(status, e.code());
  }
}
