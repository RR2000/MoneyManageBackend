# Money Manage - backend

Personal finance dashboard: bank and broker movements imported from the banks' export files, balances over
time, a portfolio graph, real estate, and a Monte Carlo projection of the portfolio.
This is the REST API (Spring Boot); the web app is in
[money-manage-app](https://github.com/RR2000/money-manage-app).

Demo: https://money.raoulrondinella.dev - register with any username; tick "sample data" to start with a
made-up year of movements, or leave it empty and upload your own files (or "Accedi con GitHub"). Every account
sees only its own data.

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
- **Broker**: Degiro transactions CSV (`POST /api/brokers/transactions/upload`), worth over time from the
  prices of each ISIN. Weekly prices, kept in the database, come from Alpha Vantage (`MONEY_PRICES_API_KEY`, free
  plan: 25 requests a day and one a second, counted and queued by the backend), with Yahoo Finance as last resort.
  The Alpha Vantage symbol of an ISIN is found once: listings from OpenFIGI (no key, kept for good for everybody),
  turned into symbols by exchange (London `.LON`, Amsterdam `.AMS`, XETRA `.DEX`...) and checked with one
  SYMBOL_SEARCH, preferring a listing in euro. Prices are asked only for an ISIN without prices or with prices older
  than a week, at most once a day per ISIN (also after a failure), all at once and for at most 3 s per request; a
  slower download ends in the background and the graph shows what is there. An ISIN without prices is left out, not
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
- **Real estate**: CRUD with total estimated value.
- **Monte Carlo**: projection of the portfolio from its historical monthly returns (percentiles P5-P95).
- **Accounts**: registration and login with a JWT, optional "Accedi con GitHub" (an account is found again only by a
  verified GitHub email), optional "forgot password" by email (`POST /api/auth/password/forgot`, then
  `/api/auth/password/reset` with the token of the link). The account's email is set from a confirmation link
  (`POST /api/account/email`, then `/api/auth/email/confirm`), also at registration. Each user has a PostgreSQL schema of their own
  (Hibernate multi-tenancy, schema per user): data never mix.

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
