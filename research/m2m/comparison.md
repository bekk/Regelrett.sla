# How this compares to the other auth mechanisms already in this codebase

After adding `GET /api/m2m/slo`, five different trust models coexist side by
side on different endpoints:

| Mechanism | Where used | Identity proven | Authorization check | Trust model |
|---|---|---|---|---|
| Delegated OBO + team-group check | `/api/answer`, `/api/answers`, `/api/context*` | A real signed-in user (delegated token, `scp` claim) | `AuthService.hasTeamAccess`/`hasContextAccess` in `AuthService.kt` — on-behalf-of exchange to Microsoft Graph, live group-membership lookup on every request | Dynamic, per-user, per-team; access disappears the moment the user leaves the Entra group |
| No app-level auth, network only | `/contextMetrics`, `/api/schemas` | None | None — registered outside any `authenticate {}` block | Purely the cluster's `NetworkPolicy` (`k8s/networkpolicy.yaml`): default-deny, only the Backstage pod is allowed in |
| HMAC shared secret | `POST /webhook` (Airtable) | Not an identity — proof of knowing a static per-provider secret | Signature comparison against the request body on every call (`AirTableWebhookRouting.kt`) | Stateless, no issuance/expiry, tied to one external system's webhook |
| App-role M2M (Entra) | `/api/m2m/slo` | An **application**, not a user — a single dedicated app registration, proven via a [federated credential](./concepts/federated-identity.md) (no secret exists for it at all) | `roles` claim check for `SLO.Read`, inside the *same* `auth-jwt` JWT validation already used for the first row | Cryptographically pinned to one exact GitHub Actions repo/workflow/branch; the role is granted only to that one identity, so no shared secret, human login, or other application can ever obtain it |
| **Direct GitHub OIDC verification (new)** | `/api/m2m/slo`, layered on top of the row above | GitHub Actions itself, via its own OIDC token (`iss=token.actions.githubusercontent.com`) | Manual signature + `repository`/`ref` claim check inside the route handler (`GithubActionsOidcVerifier.kt`), independent of the `auth-jwt` pipeline | A second, independently-rooted check against a completely separate issuer/JWKS than Entra's — a caller needs a genuine token from an actual run of this exact repo's workflow even if the Entra side were ever misconfigured |

The Entra app-role mechanism doesn't add a parallel auth pipeline for its
own check — it plugs into the *same* `auth-jwt` provider
(`src/main/kotlin/no/bekk/authentication/Authentication.kt`) already
validating signature/issuer/audience for logged-in users — that code
doesn't care whether a token is delegated (has `scp`) or app-only (has
`roles`). The direct GitHub OIDC check genuinely is a second, parallel
pipeline (it can't share the `Authorization` header with the Entra bearer
token, so it travels in a separate `X-GitHub-OIDC-Token` header and is
verified manually, the same "read a header, verify it by hand" pattern
`AirTableWebhookRouting.kt` already uses). Both checks live in
`src/main/kotlin/no/bekk/routes/M2mSloRouting.kt`.

It sits deliberately between the two extremes already in this codebase:
more identity-aware than "no auth, just network trust" or "a shared
secret," but without requiring a human user or a team membership the way
the main `/api/*` routes do — which is exactly the gap a headless,
cross-team job falls into.
