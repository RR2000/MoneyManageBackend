# MoneyManage - backend

Personal finance dashboard: bank and broker movements imported from the banks' export files, balances over
time, a portfolio graph, real estate, and a Monte Carlo projection of the portfolio.
This is the REST API (Spring Boot); the web app is in
[MoneyManageFrontend](https://github.com/RR2000/MoneyManageFrontend).

Demo: https://money.raoulrondinella.dev - register with any username; tick "sample data" to start with a
made-up year of movements, or leave it empty and upload your own files (or "Accedi con GitHub"). Every account
sees only its own data.

## What it does

- **Bank movements**: upload of Revolut and Degiro CSV, Unicredit CSV, Intesa Sanpaolo XLSX
  (`POST /api/banks/transactions/{bank}/upload`, multipart field `multipartFile`). Given today's balance of an
  account, the balance of every past day is rebuilt and kept per day
  (`POST /api/banks/transactions/accounts/{account}/balance`, body `{"amountToday": 1234.56}`).
- **Broker**: Degiro transactions CSV (`POST /api/brokers/transactions/upload`), worth over time from the
  prices of each ISIN. Prices come from Yahoo Finance (bundled, adapted copy of the
  [yahoofinance-api](https://github.com/sstrickx/yahoofinance-api) client) and are cached in the database.
- **Real estate**: CRUD with total estimated value.
- **Monte Carlo**: projection of the portfolio from its historical monthly returns (percentiles P5-P95).
- **Accounts**: registration and login with a JWT, optional "Accedi con GitHub" (an account is found again only by a
  verified GitHub email). Each user has a PostgreSQL schema of their own
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
| `MONEY_CORS_ORIGINS` | empty (closed) | only for a web app on another origin |
| `MONEY_API_DOCS` | `false` | `true`: OpenAPI description and Swagger UI on `/v3/api-docs` and `/swagger-ui.html` |

## Security

- Every user has a schema `u<id>` of their own, chosen by the server from the logged-in account (never from the
  request); the tests check that two users never see each other's data, endpoint by endpoint.
- Passwords with BCrypt; login tokens signed HMAC-SHA256, valid 24 hours. "Accedi con GitHub" links only a
  verified email.
- Login: after 5 wrong passwords a username is refused for 15 minutes (for every username, existing or not);
  at most 30 registrations every 15 minutes for the whole site. The reverse proxy also limits per address.
- Errors: unexpected ones answer "Unexpected error" and are logged with the exception class and the endpoint,
  never with their message (it can hold amounts or descriptions); SQL errors are not logged.
- Uploads up to 10 MB; amounts never travel in a URL (they would end up in the access logs).

## Image

The CI publishes `ghcr.io/rr2000/moneymanage-backend:main` (and `:sha-<commit>`) at every push on `main`.
The web app image (nginx) serves the app and forwards `/api` to the service `api` on port 8080.

## License

GPL-3.0, see [LICENSE](LICENSE).
