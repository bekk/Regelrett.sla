# Setup

See [concepts/](./concepts/) if any of the terms below (app role, member
type, client credentials, federated identity) are unfamiliar, and
[`../README.md`](../README.md) for why this is designed around three
independent trust chains.

You'll need Application Administrator (or Owner on the relevant app
registrations) rights in Entra ID, and IAM/GKE admin rights in the GCP
project, to do this setup.

## 1. Entra ID: the app role (on the existing "Regelrett" app registration)

1. Azure Portal → **Microsoft Entra ID** → **App registrations** → open the
   app that `oauth.client_id` points to.
2. Left menu → **App roles** → **Create app role**.
3. Fill in:
   - **Display name**: `SLO Export (All teams)`
   - **Allowed member types**: **Applications** (see
     [concepts/member-types.md](./concepts/member-types.md) — this is what
     makes it impossible for any human login to ever carry this role)
   - **Value**: `SLO.Read` — this exact string is what
     `src/main/kotlin/no/bekk/routes/M2mSloRouting.kt` checks for
   - **Description**: e.g. "Allows reading all filled context/answer data
     across teams for the Sikkerhetskontroller and Driftskontinuitet
     schemas (SLO export job only)"
4. Save.

If a role with this value was ever self-granted to the Regelrett app's own
service principal during earlier experimentation, remove that grant now
(API permissions → the `SLO.Read` entry under Regelrett itself →
remove). The whole point of this design is that only the dedicated app
below can ever hold it.

## 2. Entra ID: a dedicated, secret-less app registration for this one workflow

The calling workflow lives in **`bekk/grafana-sla`**, not this repo — the
federated credential below is pinned to *that* repo's workflow.

1. App registrations → **New registration**. Name it something that makes
   its single purpose obvious, e.g. "Regelrett CI — Context Export".
   Single tenant. No redirect URI needed.
2. **Do not create a client secret for this app.** That's deliberate — its
   only way to authenticate is the federated credential below.
3. On this new app → **Certificates & secrets** → **Federated credentials**
   → **Add credential** → scenario "GitHub Actions deploying Azure
   resources" (the generic GitHub OIDC template):
   - Organization/repository/entity type: pin it to `bekk/grafana-sla` and,
     at minimum, a specific branch (`ref:refs/heads/main`) or GitHub
     environment — whichever that workflow actually runs under. If your
     tenant's federated-credential UI supports pinning to a specific
     workflow file (`job_workflow_ref`-based subject), use that for the
     tightest possible scope.
   - Issuer ends up as `https://token.actions.githubusercontent.com`
     automatically.
4. **API permissions** → **Add a permission** → **My APIs** → select
   **Regelrett** → **Application permissions** → check
   `SLO.Read` → **Add permissions** → **Grant admin consent for
   `<tenant>`**.
5. Note this app's **Application (client) ID** and the **tenant ID** — these
   go into the GitHub repo as plain (non-secret) variables:
   `EXPORT_JOB_CLIENT_ID`, `EXPORT_JOB_TENANT_ID`. Also record the existing
   Regelrett app's client ID as `REGELRETT_CLIENT_ID` if it isn't already a
   repo variable — none of these three are secrets; there's nothing
   sensitive to protect about a client ID or tenant ID on their own.

## 3. Backend config: the `github_actions_oidc` section (Wall 3)

The backend also directly verifies GitHub Actions' own OIDC token — see
`src/main/kotlin/no/bekk/authentication/GithubActionsOidcVerifier.kt`. This
is config-driven, but unlike `oauth:`, most of it is already a real default
in `conf/defaults.yaml` — `audience` and `expected_repository` are fixed
facts of this codebase (not secrets, not per-environment values), so
there's nothing to set up in `conf/custom.yaml` or a k8s secret/configmap
for them:

```yaml
github_actions_oidc:
  issuer: "https://token.actions.githubusercontent.com"       # GitHub's own, fixed
  jwks_url: "https://token.actions.githubusercontent.com/.well-known/jwks" # GitHub's own, fixed
  audience: "api://regelrett-m2m-slo"      # must match the audience passed to core.getIDToken(...) in the workflow
  expected_repository: "bekk/grafana-sla" # must match the calling repo's "owner/repo" exactly
  expected_ref: null                        # e.g. "refs/heads/main" once this leaves POC - the one knob worth tightening per environment
```

Only `expected_ref` is meant to vary per environment/branch — set it in
`conf/custom.yaml` (or the deployed environment's config override) once
this leaves the POC branch.

## 4. GCP: network reachability from `bekk/grafana-sla`'s workflow

Unlike Wall 2/3, this one genuinely **is** a new GCP trust relationship —
the calling workflow runs in a different repo (`bekk/grafana-sla`) than the
one `deploy-main.yml`'s WIF trust was set up for, so that trust can't just
be "reused" as earlier notes here assumed. `bekk/grafana-sla` has its own
research on this exact question
(`research/steps/01-auth-gcp/README.md` in that repo) with an already
**confirmed-working** mechanism: a dedicated, narrowly-scoped GCP service
account reached via its own WIF trust, calling through the GKE API
server's built-in service-proxy (`kubectl get --raw .../proxy/<path>`) —
not `kubectl exec`. Follow that design for Wall 1 rather than the
`kubectl exec` sketch below, which was written before this repo split was
known and is kept here only as a fallback reference. Either way, confirm
whichever identity is used can reach the target namespace:

```bash
kubectl auth can-i create pods/exec \
  -n ns-regelrett \
  --as <the deploy service account's principal>
```

This identity already runs `kubectl apply` + rollout against the whole
namespace for every deploy, so `pods/exec` is a lateral capability it
likely already has via its existing RBAC binding — not a new elevation of
trust. If the check above says `no`, that binding needs a
`pods/exec: create` grant added.

## 5. Verify the Entra side before running the real workflow

A genuinely useful negative test, since there's no secret to test with
directly: confirm nobody can manually impersonate the new app.

```bash
az login --service-principal \
  -u <EXPORT_JOB_CLIENT_ID> -p <anything> --tenant <EXPORT_JOB_TENANT_ID>
```

This should fail — there is no password that works, by design. The only
way to authenticate as this identity is a real OIDC token from an actual
run of the pinned workflow in `bekk/grafana-sla` (`azure/login@v2` with
`client-id`/`tenant-id` and no `client-secret` — `m2m-slo-export.yml` in
*this* repo is a reference sketch of that workflow's shape, not the
workflow itself; the real one lives in and is run from `bekk/grafana-sla`).

## 6. Run it

`workflow_dispatch` the calling workflow from `bekk/grafana-sla`'s GitHub
Actions tab (or `gh workflow run ... -R bekk/grafana-sla`). Its three
checks — GCP auth via WIF, Entra auth via the federated credential, and the
GitHub OIDC token minted for the backend — each have to succeed
independently before the final call to `/api/m2m/slo` can run and be
accepted.

## Troubleshooting

| Symptom | Likely cause | Reference |
|---|---|---|
| `azure/login` fails with an AADSTS error about the federated credential | Subject in the federated credential doesn't match this exact repo/branch/environment, or the workflow doesn't have `permissions: id-token: write` | [AADSTS error codes](https://learn.microsoft.com/en-us/entra/identity-platform/reference-error-codes), [Configure trust for GitHub Actions](https://learn.microsoft.com/en-us/entra/workload-id/workload-identity-federation-create-trust-github) |
| `az account get-access-token --resource api://...` returns a token with no `roles` claim | Admin consent not granted on the dedicated app (step 2.4) | [Admin consent](https://learn.microsoft.com/en-us/entra/identity-platform/v2-admin-consent) |
| Endpoint returns `401` with no `X-GitHub-OIDC-Token` reason logged | Entra token `aud` doesn't match `oauth.client_id`, or malformed/expired | `Authentication.kt`'s `jwt("auth-jwt")` block |
| Endpoint returns `401` with a "GitHub OIDC token verification failed" log line | `github_actions_oidc.audience` in backend config doesn't match the audience passed to `core.getIDToken(...)` in the workflow, or `expected_repository`/`expected_ref` doesn't match this exact repo/branch | `GithubActionsOidcVerifier.kt`, step 3 above |
| Endpoint returns `403` with a token that has `roles` | Role `value` string doesn't exactly match `SLO.Read` in `M2mSloRouting.kt`, or the role somehow also ended up granted elsewhere — re-check step 1 | — |
| `kubectl exec` step fails with a permissions error | Deploy service account lacks `pods/exec` in the namespace | Step 4 above |
| Can't find the pod with the label selector | `gcp-config.yaml`'s `app` value doesn't match the deployment's actual `app.kubernetes.io/name` label | `k8s/deployment.yaml` |
