-- Single sign-on of the site (OpenID Connect, AUTH_MODE both or oidc): the account is found again by the identity
-- provider's subject ("sub" of the id token), set at the first login with it. Linked to an existing account only
-- through a verified email equal to the account's confirmed one (see OidcAccounts).
ALTER TABLE users ADD COLUMN idp_subject VARCHAR(255) UNIQUE;
