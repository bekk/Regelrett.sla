# Client credentials flow

This is the OAuth2 grant type used when there's no user to authenticate —
the application authenticates as *itself*, using its own credentials (a
client secret, or better, a [federated identity credential](./federated-identity.md)),
and gets back a token representing the application, not a person.

```
POST https://login.microsoftonline.com/<TENANT_ID>/oauth2/v2.0/token
  grant_type=client_credentials
  client_id=<CLIENT_ID>
  client_secret=<CLIENT_SECRET>        (or a federated-credential assertion instead)
  scope=<CLIENT_ID>/.default
```

The resulting access token's `aud` (audience) is the target app
(`CLIENT_ID`), and — crucially for this project — if the calling service
principal has been granted an [app role](./app-roles.md) with member type
`Application`, that role's value shows up in the token's **`roles`** claim.
This is the app-only counterpart to a delegated (user) token's `scp`
(scopes) claim.

Because the existing `auth-jwt` Ktor provider
(`src/main/kotlin/no/bekk/authentication/Authentication.kt`) only validates
a token's signature, issuer, and audience — it doesn't look at `scp` vs.
`roles` — it accepts both delegated and app-only tokens for the same app
registration without any code changes. The only new logic needed was
reading the `roles` claim in
`src/main/kotlin/no/bekk/routes/M2mSloRouting.kt`.

References:
- [Client credentials flow (v2.0)](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-client-creds-grant-flow)
- [Access token claims reference](https://learn.microsoft.com/en-us/entra/identity-platform/access-token-claims-reference)
