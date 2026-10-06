# Federated identity credentials

A **federated identity credential** lets an app registration trust tokens
issued by an external identity provider (like GitHub Actions' OIDC issuer)
*instead of* a client secret. Entra cryptographically verifies that an
incoming token was signed by the trusted issuer and matches a specific
`subject` you configure — if both match, Entra exchanges it for a normal
Entra access token via the [client credentials flow](./client-credentials-flow.md),
with no secret ever stored or transmitted.

For GitHub Actions specifically, the trust relationship looks like:

- **Issuer**: `https://token.actions.githubusercontent.com`
- **Subject**: pinned to an exact repo + workflow + branch/environment,
  e.g. `repo:<org>/<repo>:ref:refs/heads/main` or
  `repo:<org>/<repo>:environment:<name>`

Only a workflow run matching that exact subject can ever authenticate as
this identity — not "anyone who happens to have a copy of a secret," but
"cryptographically, only this specific repo/workflow/branch." There's
nothing to leak, rotate, or accidentally reuse in another project. See
[`../README.md`](../README.md) for why this is the only mechanism strong
enough to actually guarantee "only this one GitHub Action, nobody else."

## This integration needs *two* separate federated-identity setups — don't conflate them

Both of these trust **`bekk/grafana-sla`**'s calling workflow — not this
repo's own `deploy-main.yml`, which is a third, unrelated WIF trust
(Regelrett's own GCP deploy identity, trusting *this* repo's workflow).
GCP calls its mechanism "Workload Identity Federation," and Microsoft
separately calls its own, equivalent feature "workload identity
federation" too — same *name*, same *idea* (trust an external OIDC token
instead of a secret), but they are two **completely independent,
separately-configured trust relationships**, and the calling workflow in
`bekk/grafana-sla` uses *both of them side by side* for two different
purposes (plus a third, unrelated direct verification of GitHub's own OIDC
token in the backend — see [`../README.md`](../README.md)'s "Wall 3"):

- The **GCP** one trusts `bekk/grafana-sla`'s workflow to act as a **GCP
  service account**, and is a *new* trust relationship distinct from
  `deploy-main.yml`'s — it has to be set up specifically for that repo's
  workflow identity (`bekk/grafana-sla` already has its own validated
  design for this, see `research/steps/01-auth-gcp/` in that repo). It
  only grants network reachability — it knows nothing about Entra ID or
  app roles, and on its own it would let the workflow reach the cluster
  but not pass the `roles` claim check inside it.
- The **Entra ID** one (described on this page and set up in
  [`../setup.md`](../setup.md)) trusts the same `bekk/grafana-sla` workflow
  to act as a **dedicated Entra ID app registration**, and is what
  actually produces the `SLO.Read`-bearing access token the endpoint
  checks. It has nothing to do with reaching the cluster.

Neither federation setup can be used to bypass the other — see the
"three walls" diagram in [`../README.md`](../README.md). Setting one up
gives you none of the other for free; they had to be configured
independently.

References:
- [Workload identity federation](https://learn.microsoft.com/en-us/entra/workload-id/workload-identity-federation)
- [Configure an app to trust a GitHub Actions workflow](https://learn.microsoft.com/en-us/entra/workload-id/workload-identity-federation-create-trust-github)
