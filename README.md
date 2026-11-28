# Money Manage - backend

Personal finance: the net worth, bank and broker movements imported from the banks' export files (or straight from
the bank), balances over time, investments, real estate, other assets and debts, fixed costs and a plan with its
projection.
This is the REST API (Spring Boot); the web app is in
[money-manage-app](https://github.com/RR2000/money-manage-app).

Demo: https://money.raoulrondinella.dev/demo - one click, no password and no sign-up: a sandbox of your own with a
made-up year of movements, two made-up funds, assets and a plan, gone after 24 hours. Or register with any username
and tick "sample data", or leave it empty and upload your own files. Every account sees only its own data.

## What it does

- **Bank movements**: upload of Revolut and Degiro CSV, Unicredit CSV, Intesa Sanpaolo XLSX
  (`POST /api/banks/transactions/{bank}/upload`, multipart field `multipartFile`). Every movement has an id of its
  own, also two of the same day with the same description; uploading the same file again adds nothing (movements are
  counted by account, moment and description: the ones already there are updated, only the extra ones added). Given today's balance of an
  account, the balance of every past day is rebuilt and kept per day
  (`POST /api/banks/transactions/accounts/{account}/balance`, body `{"amountToday": 1234.56}`).
- **Degiro**: the two exports are read by column name, with Italian or English headers (and the German, Dutch,
  Spanish and French names of the same columns), commas or semicolons, decimal comma or dot. The account statement
  (Account.csv, bank upload `Degiro`) keeps the currency of every row: a dividend in USD stays in USD, nothing is
  converted. The transactions (Transactions.csv) keep the currency of the price and of the fee and the Order ID:
  the same file again, or the same executions exported in another language, add nothing (by Order ID; rows imported
  before it was read are found by moment, ISIN, quantity and price and get it), and two fills of one order in the
  same minute are two rows.
- **Broker**: Degiro transactions CSV (`POST /api/brokers/transactions/upload`), valued at the prices of each
  ISIN. Weekly prices, kept in the database, come from Alpha Vantage (`MONEY_PRICES_API_KEY`, free
  plan: 25 requests a day and one a second, counted and queued by the backend), with Yahoo Finance as last resort.
  The Alpha Vantage symbol of an ISIN is found once: listings from OpenFIGI (no key, kept for good for everybody),
  turned into symbols by exchange (London `.LON`, Amsterdam `.AMS`, XETRA `.DEX`...) and checked with one
  SYMBOL_SEARCH, preferring a listing in euro. Prices are asked only for an ISIN without prices or with prices older
  than a week, at most once a day per ISIN (also after a failure), all at once and for at most 3 s per request; a
  slower download ends in the background and the page shows what is there. An ISIN without prices is left out, not
  an error. On the investments page (`/api/instruments`) the symbol can be set by hand after a preview of its last
  price, a quote currency other than the euro is flagged (never converted), and prices can be imported from a CSV
  (`date,price` per line).
- **Open banking** (optional, only for the users listed in `MONEY_BANKING_USERS`): movements and balances straight
  from the bank through [Enable Banking](https://enablebanking.com) (an application in restricted mode, for its owner's
  own accounts). `GET /api/banking/aspsps?country=IT` lists the banks; `POST /api/banking/links` `{"aspsp","country"}`
  answers the bank's `url`, where the user gives the consent (as long as the bank allows, e.g. 180 days); the bank
  sends the browser back to `/api/banking/callback`, which redirects to `/banks?result=ok|error`. `GET /api/banking/links`
  shows links, accounts (masked IBAN, last balance) and errors as codes; `PUT /api/banking/accounts/{id}`
  `{"moneyAccount"}` picks the money account of a bank account (an existing one: from the day after its last movement;
  a new name: as far back as the bank goes); `POST /api/banking/sync` syncs now; `DELETE /api/banking/links/{id}`
  closes the consent (the movements stay). Syncs at 06:30 and 18:30 (Europe/Rome) from 5 days before the last booked
  movement; only booked movements, with the bank's id (or a hash of the movement when the bank gives none) so nothing
  arrives twice, matched to the rows already there from a file (same amount and currency, same day or one day
  apart; more rows than movements = ambiguous, counted, nothing added); the bank's balance rebuilds the balance history.
  A rate limit of the bank pauses the link for 6 hours; a consent about to end sends one mail 7 days before (if mail
  is on and the account has an email). Every movement says where it comes from (`source`: `FILE`, `MANUAL`, `BANK`).
- **Money accounts**: `GET /api/money-accounts` lists every account with its rows by source, first and last day,
  last balance and currency, and the `id` of its page (12 hex characters of the SHA-256 of the name: the name never
  goes in a URL). `GET /api/money-accounts/{id}` gives balance, this month's money in and out and whether a bank
  imports there; `GET /api/money-accounts/{id}/balances?from=` the balance at the end of each day;
  `POST /api/money-accounts/{id}/movements` `{"q","kind":"ALL|IN|OUT","uncategorized","offset","limit"}` the movements
  newest first, searched by words of the description or by amount (the search travels in the body), each with its
  category; `POST /api/money-accounts/movements` the same over every account (e.g. `{"uncategorized":true}`: the ones
  still without a category). `POST /api/money-accounts/delete`
  `{"name","confirm"}` deletes an account no bank imports into.
- **Overview**: `GET /api/net-worth?from=YYYY-MM-DD` gives the net worth on `from` (left out: the first day with
  data), at the end of every month after it and today, oldest first, each split in cash (the balance of every
  account on its last day with a balance until then), investments (the worth of the portfolio at its last price
  until then), real estate (the estimated value, from the purchase date on), other assets (the estimated value,
  from the day of the estimate on, or on every day when it has none) and debts (negative: what is left today, also on
  the days before, because the past of a debt is not rebuilt: a loan paid every month made the net worth of the past
  look a little better than it was); plus `today`, `before` (30 days ago, for the change) and how many accounts,
  properties, other assets, debts and instruments are in it. Only euro: anything in another currency is left out of
  the total, nothing is converted, and `excluded` says how many of each and which currencies. `GET /api/money-accounts/movements/latest?limit=8` gives the newest movements of every
  account (at most 50), each with its account and the `accountId` of its page.
- **Investments**: `GET /api/investments` gives every position (ISIN) with name, quantity, average cost (weighted
  average cost: a purchase adds quantity times price plus its fee, a sale takes out the average cost of what it sold
  and the rest is the realised gain), the cost of what is held (`invested`), the value at the last known price and its
  date, the gain in euro and as a share, the tax if it were sold today (26% of the gain, only when positive, position by
  position: an estimate) and the gain after it, its share of the portfolio and its target; the totals; the returns
  from the real trades: money-weighted (`xirr`, a year: purchases with their fees and sales net of them are the flows,
  today's value the last one), the simple gain on what was spent on purchases, and time-weighted (`twr` for the whole
  period and `twrYearly`: the months chained, modified Dietz inside each month, so it does not depend on when money was
  paid in); the allocation against the targets (percent of the portfolio now and wanted, and the gap in euro: positive
  = buy about that much); the history (from the first trade, the end of every month and today: value, money put in =
  purchases minus sales, value by ISIN). Only euro: a position traded or quoted in another currency, or without any
  price, is flagged (`excluded`: `CURRENCY`, `NO_PRICE`), not converted and left out of totals, returns, allocation
  and history. `GET /api/investments/targets` and `PUT /api/investments/targets` `{"targets":[{"isin","percent"}]}`
  read and replace the targets: an ISIN of the user at most once, 0 to 100 with at most two decimals (0: none),
  together at most 100 (the rest is "not assigned"); otherwise 400 `TARGET_RANGE`, `TARGET_SUM`, `UNKNOWN_ISIN`,
  `DUPLICATE_ISIN`.
- **Assets**: `GET /api/assets` gives the real estate, the other assets and the debts (largest first) and their
  totals today by currency (`totals`: euro first and always there, then the others; real estate, other assets, debts
  as a negative amount, and `net` = the three together; currencies are never added up or converted). Real estate counts
  from its purchase date on, as in the net worth. Every debt says how it ends (`payoff`): `END_DATE` (the date given),
  `ESTIMATE` (no date: from the monthly payment P and the yearly rate, n = ceil(-ln(1 - r B / P) / ln(1 + r)) months
  with r the monthly rate and B what is left, B / P without interest), `NEVER` (the payment does not cover the
  interest, or more than 100 years), `UNKNOWN` (no date, no payment), `PAID` (nothing left), with `expectedEnd` and
  `monthsLeft` (rounded up, negative when the end date has passed). Other assets: `POST /api/assets/others`,
  `PUT|DELETE /api/assets/others/{id}` with `{"name","kind","value","currency","valuedOn"}` (`kind`: `VEHICLE`, `GOLD`,
  `CASH` for money in another currency, `RECEIVABLE` for money lent, `OTHER`; `valuedOn`: the day of the estimate,
  optional, not in the future). Debts: `POST /api/assets/debts`, `PUT|DELETE /api/assets/debts/{id}` with
  `{"name","balance","currency","annualRate","monthlyPayment","endDate"}` (rate in percent, payment and end date
  optional). Real estate: `GET|POST /api/realestate`, `GET|PUT|DELETE /api/realestate/{id}` with name, address, purchase
  date and price, estimated value, monthly rent and expenses, currency. What is refused is a 400 with a code:
  `NAME_REQUIRED`, `NAME_TOO_LONG` (200), `TEXT_TOO_LONG` (300), `AMOUNT_REQUIRED`, `AMOUNT_RANGE` (amounts 0 or more,
  at most two decimals and a thousand billion), `CURRENCY` (an ISO 4217 code), `KIND`, `RATE_RANGE` (0 to 100, three
  decimals), `DATE_IN_FUTURE`, `DATE_RANGE` (1900 to 100 years ahead), `TOO_MANY` (500 of a type); an id that is not
  there is a 404.
- **Categories and fixed costs**: every movement can have a category (`GET|POST /api/categories`,
  `PUT|DELETE /api/categories/{id}` with `{"name","fixed"}`; `fixed`: a fixed cost, like rent, bills, insurance). Rules
  give it (`POST /api/categories/rules`, `PUT|DELETE /api/categories/rules/{id}` with
  `{"categoryId","pattern","direction":"IN|OUT"|null,"accountId"|null}`): a description that contains the text, without
  caring about upper case and repeated spaces, gets the category; when more rules match, the longest text wins. The
  rules work on every new movement, from a file, typed in or from the bank (also the next syncs); the movements already
  there change only with `POST /api/categories/apply` `{"dryRun":true}` (how many would get a category, change it, lose
  it) and then `{"dryRun":false}`. `PUT /api/movements/{id}/category` `{"categoryId","rule":{"pattern","direction",
  "onlyThisAccount"}}` chooses the category of one movement by hand (no rule changes it any more; `categoryId` null frees
  it), optionally with a rule made from it, and answers what "apply" would change after it. Every movement says its
  category and `categorySource` (`RULE`, `MANUAL`); `GET /api/categories` also counts the movements without one.
  Deleting a category deletes its rules, its movements stay without one. Codes: `NAME_REQUIRED`, `NAME_TOO_LONG` (60),
  `NAME_TAKEN`, `TOO_MANY` (200 categories, 1000 rules), `PATTERN_REQUIRED`, `PATTERN_TOO_SHORT` (2), `PATTERN_TOO_LONG`
  (100), `DIRECTION`, `ACCOUNT`, `CATEGORY`, `RULE_EXISTS`.
  `GET /api/fixed-costs?months=12` (1 to 36) gives, over the last complete months (this month is not complete; later
  when the data start later: `monthsUsed`, `from`, `to`), for every fixed category and in all: what went out minus the
  refunds (`total`), the average a month (`monthly`), the year (`yearly` = monthly x 12, so a cost paid once a year counts
  for a twelfth every month), this month so far (`thisMonth`); `setAside`, the monthly average rounded up to the next 10
  euro (`roundingStep`); and what comes next (`upcoming`), for every rule of a fixed category (the movements given by
  hand without one: one group) and currency: the cadence from the dates of all the payments (one per day), that is the
  median gap if at least 60% of the gaps are in the same band (`MONTHLY` 26-35 days, `BIMONTHLY` 54-68, `QUARTERLY`
  82-100, `SEMIANNUAL` 165-200, `ANNUAL` 340-390) with at least 3 payments (2 for half-yearly and yearly), otherwise
  `UNKNOWN`; `expectedOn` = last payment + cadence (`late` once passed; a whole cadence late it has stopped and is left
  out), `amount` = median of the last 6 payments. Only euro in the totals: other currencies are in `otherCurrencies`,
  never converted. The sample data come with seven categories and their rules (four fixed, with a monthly, a
  two-monthly and a yearly cost); real accounts start with none.
- **Plan**: where the investments are going, in place of a spreadsheet. `GET /api/plans` lists the plans (the one saved
  last first, each with planned against real) and the inputs from money: the value of the investments today, the money
  put in (`invested`, the cost of what is held: the base of the taxes), the monthly payment (purchases with their fees
  minus sales of the last 12 months, divided by 12, or by the months since the first purchase when fewer; never below
  zero; `monthlyMonths`) and the yearly return so far (`historicalRate`: the XIRR of the investments page, in percent, for
  reference). `POST /api/plans` `{"name","config"}` creates a plan, `PUT /api/plans/{id}` saves a new version (the ones
  before stay: `GET /api/plans/{id}?version=n`), `DELETE /api/plans/{id}` deletes it with its versions; "duplicate as a
  scenario" is a `POST` with the values of another. `config`: `targetDate`, `startValue`, `invested`, `monthly`, each with
  `*Own` (true: the user's own value; false: on saving money's value of that day is taken instead), `inflation`,
  `taxRate`, `ratePrudent`, `rateBase`, `rateOptimistic` (percent a year; empty: 2, 26, 3, 5, 7) and `events`
  (`{"kind":"ONE_OFF|MONTHLY","date","amount","label"}`: money in (+) or out (-) once in the month of the date, or the
  monthly payment changing by the amount from that month on). `POST /api/plans/projection` `{"config","monteCarlo"}`
  computes without saving. The arithmetic, month by month from this month (0) to the month of the target date: the
  payment of the month goes in, then the events of the month, then the month grows, V = V x (1 + r_m) with r_m =
  (1 + r)^(1/12) - 1 (twelve months give exactly r; r / 12 would give more). Money in adds to the value and to the money
  put in; money out takes from the value (never below zero) and lowers the money put in in the same proportion. At the
  target date, for each scenario: before taxes (`gross`), the tax on the gain only, `taxRate x max(0, value - invested)`,
  after it (`afterTax`) and after it in euro of today (`real` = afterTax / (1 + inflation)^years, with the exact years
  from today to the target date: whole years plus the days left over the length of that year); and the values month by
  month (`labels` yyyy-MM, `prudent`, `base`, `optimistic`, `invested` of the base one). Monte Carlo
  (`monteCarlo: true`): 2000 paths with the same payments and events, each month growing by exp(m + s z) with z normal and
  m, s the mean and standard deviation of the real monthly log returns of the portfolio (from the history of the
  investments page, end of every complete month, the money put in during the month taken out); percentiles `p5`, `p50`,
  `p95` at every month of the plan; fixed seed (the same plan gives the same band); fewer than 6 months of history:
  `available: false`, `HISTORY_TOO_SHORT`. Planned against real: the value of the investments at the end of every month
  from the month of the first version is kept (table `plan_snapshot`), taken on the first of the next month at 04:15
  (Europe/Rome) or filled in from the history of the investments when missing (also on reading); a month kept is never
  changed. `tracking` compares it with the base scenario of the version from the day it was saved (in the month of saving
  nothing happens, then one step a month): `status` `ON_TRACK` (within 5% of the planned value), `ABOVE`, `BELOW`, with
  `gap` (actual - planned) and `gapShare`, or `WAITING` with `nextCheck` until the first month is complete. Codes:
  `NAME_REQUIRED`, `NAME_TOO_LONG` (60), `TARGET_DATE` (from next month to 100 years), `START_RANGE`, `INVESTED_RANGE`,
  `MONTHLY_RANGE` (0 or more, two decimals), `RATE_RANGE` (-20 to 30), `RATE_ORDER` (prudent <= base <= optimistic),
  `INFLATION_RANGE` (-5 to 20), `TAX_RANGE` (0 to 100), `TOO_MANY_EVENTS` (50), `EVENT_KIND`, `EVENT_DATE` (on saving: from
  next month to the target month; the projection only leaves out the others), `EVENT_AMOUNT` (not 0, two decimals),
  `EVENT_LABEL` (60), `TOO_MANY` (20 plans), `TOO_MANY_VERSIONS` (1000). The sample data come with a plan in two versions,
  saved 12 and 5 months before. Only euro, as on the investments page.
- **Accounts**: registration and login with a JWT, optional "Accedi con GitHub" (an account is found again only by a
  verified GitHub email), optional "forgot password" by email (`POST /api/auth/password/forgot`, then
  `/api/auth/password/reset` with the token of the link). The account's email is set from a confirmation link
  (`POST /api/account/email`, then `/api/auth/email/confirm`), also at registration. Each user has a PostgreSQL schema of their own
  (Hibernate multi-tenancy, schema per user): data never mix.
- **Single sign-on** ("Continue with raoulrondinella.dev", OpenID Connect, Zitadel), chosen with `AUTH_MODE`:
  `legacy` (default: password and GitHub, the provider off even if configured), `both`, `oidc` (only the provider:
  login, registration and password reset answer 404, no GitHub). Changing the value back is the rollback: accounts
  do not change. Public client with PKCE (no secret), registration `zitadel` of Spring Security `oauth2Login`, redirect
  URI `https://<site>/api/login/oauth2/code/zitadel`. The account is the one linked to the provider's `sub`
  (`users.idp_subject`); at the first login, only with `email_verified`, the account with the same confirmed email is
  linked, otherwise a new account (with its schema) is created; an email still waiting for its confirmation link on
  some account is refused. Back at `/login#token=...&id_token=...` or `/login#oidc-error=<code>` (`email-unverified`,
  `email-unconfirmed`, `already-linked`, `unavailable`, `failed`). `GET /api/auth/providers` says `oidc`, `oidcUrl`
  and `oidcLogoutUrl` (the provider's `end_session_endpoint` with `client_id` and `post_logout_redirect_uri`
  `<MONEY_WEB_URL>/`; the web app adds `id_token_hint`). The provider is read from its discovery document after the
  start, never before: if it is down the site starts and the other logins work.
- **Link to raoulrondinella.dev** (settings page): an account logged in with its password or GitHub is linked to a
  person of the provider whatever the two emails are. `POST /api/account/oidc-link` answers `{"url"}` (the provider's
  button with `?link=<request>`): the request is signed with a key of its own (never a login token), valid 2 minutes
  and once, bound to the browser by a `__Host-money-oidc-link` cookie. The authorization request (state, PKCE, nonce)
  then stands for that account for 10 minutes and once; back from the provider the `sub` is saved on it, never by
  email: refused if another account has it (`already-linked`) or if the account has another one (`other-subject`).
  Back at `/settings#oidc-link=linked&id_token=...` or `/settings#oidc-link-error=<code>` (also `expired`,
  `unavailable`, `failed`). `DELETE /api/account/oidc-link` unlinks (409 `only-login` with `AUTH_MODE=oidc`).
  `GET /api/account` says `oidc`, `oidcLinked`, `oidcCanUnlink`; with the provider off both endpoints answer 404.

- **Demo sandboxes** ("Try the demo", `MONEY_DEMO_ENABLED=true`; off by default, then `POST /api/demo` answers 404):
  `POST /api/demo`, without a login, makes an account `demo-<10 random characters>` with no email and no password
  anybody knows, the `demo` flag and `expires_at` 24 hours later (`MONEY_DEMO_HOURS`), its own schema with the sample
  data, and answers `{"token"}` like a login (201). `GET /api/auth/providers` says `demo`; `GET /api/account` says
  `demo` and, for a sandbox, `expiresAt` and `demoHours`. Limits: `MONEY_DEMO_PER_IP` new sandboxes an hour per client
  address (3, `X-Real-IP` from swag) and `MONEY_DEMO_MAX_ACTIVE` open at the same time (50), then 429 with the code
  `DEMO_LIMIT_IP` or `DEMO_FULL` in `code` and `message`. A sandbox can write anything inside its schema (movements,
  categories, plans, assets...), but what goes outside is refused before the endpoint, with a code: 403
  `DEMO_NO_BANKING` (every `/api/banking`), `DEMO_NO_EMAIL` (`POST /api/account/email`), `DEMO_NO_SSO`
  (`/api/account/oidc-link`), `DEMO_NO_PRICES` (symbol preview and choice: Alpha Vantage), and 413
  `DEMO_UPLOAD_TOO_LARGE` with `limitKb` for a bank, broker or prices file over `MONEY_DEMO_MAX_UPLOAD_KB` (256).
  The market is never asked for the prices of a sandbox (the sample funds have their own, a file may bring more), so
  the free quota of Alpha Vantage is not spent by visitors. Past `expires_at` its token stops working; every hour
  (minute 7) a job removes the sandboxes past their end with their schema (email links and bank states go with the
  row): the delete itself requires `demo` and the `demo-` prefix, the database refuses a demo row without them, and
  nobody can register a `demo-` username, so no other account can ever be removed by it.

API docs: `/swagger-ui.html` on the backend, when `MONEY_API_DOCS=true` (on in the local run).

## Stack

Java 21, Spring Boot 4, Spring Security, JPA/Hibernate, Flyway, PostgreSQL. Tests run on an embedded
PostgreSQL (real binaries from Maven, no Docker needed).

## Run

```bash
mvn verify                      # tests
mvn spring-boot:test-run        # backend on :8080 with an embedded, empty PostgreSQL
```

With your own PostgreSQL: `DB_URL=jdbc:postgresql://host:5432/money DB_USER=money DB_PASSWORD=... mvn spring-boot:run`.
The database user must be able to create schemas in its database (one schema per registered user).

| variable | default | |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/money` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `money` / empty | credentials |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | empty (off) | GitHub OAuth App for "Accedi con GitHub", callback `https://<site>/api/login/oauth2/code/github` |
| `AUTH_MODE` | `legacy` | `legacy`, `both` or `oidc` (see "Single sign-on"); `oidc` without the two below: no start |
| `MONEY_OIDC_ISSUER` | empty (off) | issuer of the OpenID provider, e.g. `https://auth.example.org` (discovery at `/.well-known/openid-configuration`) |
| `MONEY_OIDC_CLIENT_ID` | empty (off) | client id of a public client with PKCE at the provider |
| `MONEY_JWT_SECRET` | random at every start | HMAC key (>= 32 random characters, otherwise no start) of the login tokens |
| `MONEY_PRICES_API_KEY` | empty (Yahoo and CSV only) | Alpha Vantage key for the prices of the investments (free plan) |
| `SMTP_HOST` / `SMTP_PORT` | empty (off) / `587` | SMTP relay for the emails (STARTTLS required) |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | empty (off) | SMTP credentials |
| `MAIL_FROM` | empty (off) | sender address, e.g. `noreply@example.org` (shown as "Money Manage") |
| `MONEY_WEB_URL` | empty (off) | public URL of the web app (e.g. `https://money.example.org`): the links in the emails point there |
| `MONEY_ENABLEBANKING_APP_ID` | empty (off) | Enable Banking application id (the JWT `kid`); its redirect URLs must include `<MONEY_WEB_URL>/api/banking/callback` |
| `MONEY_ENABLEBANKING_KEY` | empty (off) | the application's private key: its PEM file in base64 on one line (`base64 -w0 key.pem`) |
| `MONEY_BANKING_USERS` | empty (off) | ids of the users who may use open banking, comma separated (the others get 404) |
| `MONEY_CORS_ORIGINS` | empty (closed) | only for a web app on another origin |
| `MONEY_DEMO_ENABLED` | `false` | `true`: "Try the demo", `POST /api/demo` makes a sandbox with the sample data |
| `MONEY_DEMO_PER_IP` | `3` | new sandboxes an hour from one client address |
| `MONEY_DEMO_MAX_ACTIVE` | `50` | sandboxes open at the same time, for the whole site |
| `MONEY_DEMO_HOURS` | `24` | how long a sandbox lasts |
| `MONEY_DEMO_MAX_UPLOAD_KB` | `256` | largest file a sandbox uploads |
| `MONEY_API_DOCS` | `false` | `true`: OpenAPI description and Swagger UI on `/v3/api-docs` and `/swagger-ui.html` |

## Security

- Every user has a schema `u<id>` of their own, chosen by the server from the logged-in account (never from the
  request); the tests check that two users never see each other's data, endpoint by endpoint.
- Passwords with BCrypt; login tokens signed HMAC-SHA256, valid 24 hours. "Accedi con GitHub" links only a
  verified email.
- Login with the username (any case: usernames differing only in case cannot be registered) or with the account's
  confirmed email (also the GitHub one), same answer for every failure. A password reset also gives an account born
  from "Accedi con GitHub" a password; the mail and the reset page say the username to log in with.
- Login: after 5 wrong passwords an account is refused for 15 minutes (username and email share the count; also for
  usernames that do not exist);
  at most 30 registrations every 15 minutes for the whole site. The reverse proxy also limits per address.
- Errors: unexpected ones answer "Unexpected error" and are logged with the exception class and the endpoint,
  never with their message (it can hold amounts or descriptions); SQL errors are not logged.
- Password reset by email: on only when all the SMTP variables, `MAIL_FROM` and `MONEY_WEB_URL` are set (otherwise
  the endpoints answer 404 and the web app hides it). The request always answers 202, whether an account has the
  email or not, and the mail leaves in the background (same timing). The link holds 32 random bytes, the database
  keeps only their SHA-256; it works once, for 30 minutes, and a new password voids the other links. At most 10
  requests per hour per client address (then 429), 3 mails per hour per email and 50 per hour for the site (then
  nothing is sent, same answer). An email becomes the account's only once confirmed from a link (24 hours), so
  nobody can attach an address they do not own. Tokens travel in the body and, in the links, in the
  URL fragment: never in an access log. Emails and tokens are never logged.
- Demo sandboxes: see above. Created one at a time (the cap cannot be passed by two requests together), never with
  an email or a usable password; the limit per address is in memory (a restart forgets it), the cap is counted in
  the database. Nothing they do leaves the server: no bank, no mail, no single sign-on, no market.
- Uploads up to 10 MB; amounts never travel in a URL (they would end up in the access logs).
- Open banking: off unless all three variables and `MONEY_WEB_URL` are set, and only for the listed users (404 for
  the others). The callback has no login: it is tied to a `state` of 32 random bytes, kept only as a SHA-256, valid
  once and for 10 minutes; it redirects without any code in the URL. The bank session id and the account ids stay in
  the user's schema and are never returned; only the masked IBAN is kept. The logs hold counts, link ids, Enable
  Banking's error codes and endpoints, never a body, an id, the code, the state, an IBAN, an amount or a description.

## Image

The CI publishes `ghcr.io/rr2000/money-manage-backend:main` (and `:sha-<commit>`) at every push on `main`.
The web app image (nginx) serves the app and forwards `/api` to the service `api` on port 8080.

## License

GPL-3.0, see [LICENSE](LICENSE).
