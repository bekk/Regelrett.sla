# Regelrett i Kubernetes

Regelrett ligger på <https://regelrett.slo.fleks.dev>. Manifestene er rene YAML-filer, uten
kustomize eller Helm. **Merge til `main` deployer appen**, se [.github/README.md](../.github/README.md).
Kommandoene under er engangsoppsett og feilsøking.

## Oversikt

| Fil | Objekt | Namespace |
| --- | --- | --- |
| `namespace.yaml` | Namespace `ns-regelrett` | – |
| `configmap.yaml` | ConfigMap `config-regelrett` | `ns-regelrett` |
| `deployment.yaml` | Deployment `deployment-regelrett` | `ns-regelrett` |
| `service.yaml` | Service `service-regelrett` | `ns-regelrett` |
| `httproute.yaml` | HTTPRoute `regelrett.slo.fleks.dev` + HealthCheckPolicy | `ns-regelrett` |
| `networkpolicy.yaml` | NetworkPolicy default-deny + allow fra Backstage og lastbalanseren | `ns-regelrett` |
| `db/namespace.yaml` | Namespace `ns-regelrett-db` | – |
| `db/configmap-initdb.yaml` | ConfigMap `config-postgres-initdb` | `ns-regelrett-db` |
| `db/postgres.yaml` | Service + StatefulSet | `ns-regelrett-db` |
| `db/networkpolicy.yaml` | NetworkPolicy default-deny + allow fra appen | `ns-regelrett-db` |

`httproute.yaml` kobler appen på den delte Gatewayen i kartverket.dev.sla (`k8s/gateway/`).
Appregistreringen i Entra ID må ha redirect-URI-en `https://regelrett.slo.fleks.dev/callback`.

Databasen har eget namespace, så dataene overlever at `ns-regelrett` slettes.

## Secrets

Tre Secrets må finnes før poddene starter, ellers står de i `CreateContainerConfigError`.

| Secret | Namespace | Nøkler | Lest av |
| --- | --- | --- | --- |
| `secret-regelrett-postgres` | `ns-regelrett-db` | `POSTGRES_PASSWORD`, `APP_DB_PASSWORD` | postgres-imaget og init-SQL-en |
| `secret-regelrett-db` | `ns-regelrett` | `RR_DATABASE_USER`, `RR_DATABASE_PASSWORD` | appen |
| `secret-regelrett-oauth` | `ns-regelrett` | `RR_OAUTH_*`, `RR_AIRTABLE_ACCESS_TOKEN` | appen |

Fyll inn de gitignorerte env-filene. De er to, fordi `--from-env-file` tar med alle nøklene i
filen, og OAuth-hemmelighetene ikke skal havne i databasens namespace.

```bash
cp k8s/secrets/postgres.env.example k8s/secrets/postgres.env
cp k8s/secrets/oauth.env.example k8s/secrets/oauth.env
openssl rand -hex 24   # passord som ikke trenger siteres
```

Lag Secretene når namespacene finnes (se [Deploy](#deploy)). `secret-regelrett-db` leser
passordet fra `postgres.env`, så de to ikke kan gå i utakt. Brukeren er `regelrett` fordi
init-SQL-en hardkoder rollenavnet.

```bash
kubectl create secret generic secret-regelrett-postgres -n ns-regelrett-db --from-env-file=k8s/secrets/postgres.env --dry-run=client -o yaml | kubectl apply -f -
set -a && . ./k8s/secrets/postgres.env && set +a && kubectl create secret generic secret-regelrett-db -n ns-regelrett --from-literal=RR_DATABASE_USER=regelrett --from-literal=RR_DATABASE_PASSWORD="$APP_DB_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic secret-regelrett-oauth -n ns-regelrett --from-env-file=k8s/secrets/oauth.env --dry-run=client -o yaml | kubectl apply -f -
```

`--dry-run=client -o yaml | kubectl apply -f -` gjør kommandoene trygge å kjøre på nytt.
Poddene leser Secretene bare ved oppstart, så restart etter en endring:

```bash
kubectl rollout restart deployment/deployment-regelrett -n ns-regelrett
```

## Deploy

Namespacene må finnes før Secretene, og Secreten og initdb-ConfigMapen før databasen starter.

```bash
kubectl apply -f k8s/namespace.yaml -f k8s/db/namespace.yaml
# lag Secretene over
kubectl apply -f k8s/db/configmap-initdb.yaml -f k8s/db/postgres.yaml -f k8s/db/networkpolicy.yaml
kubectl rollout status statefulset/statefulset-regelrett-postgres -n ns-regelrett-db --timeout=5m
```

Appen deployes av CI. `deployment.yaml` har plassholderen `replaced-at-deploy` i stedet for en
tag, så en bar `kubectl apply -f k8s/deployment.yaml` feiler med vilje. For hånd:

```bash
IMG=europe-north1-docker.pkg.dev/gcp-fleks-grafanaslo/spire-grafana-slo/spire-regelrett:<tag>
awk 'FNR==1 && NR>1 {print "---"} {print}' k8s/*.yaml \
  | sed "s|replaced-at-deploy|$IMG|g" \
  | kubectl apply -f -
```

Neste merge til `main` overskriver det du deployet for hånd.

### Image

Imaget må refereres med full sti. Et bart `spire-regelrett:<tag>` tolkes som Docker Hub og
gir `ImagePullBackOff`.

```bash
gcloud artifacts docker images list europe-north1-docker.pkg.dev/gcp-fleks-grafanaslo/spire-grafana-slo/spire-regelrett --include-tags
```

Bygger du selv, må det være for `linux/amd64`. Et `arm64`-image fra en Apple Silicon-Mac
pulles fint, men krasjer med `exec format error`.

```bash
gcloud auth configure-docker europe-north1-docker.pkg.dev
IMG=europe-north1-docker.pkg.dev/gcp-fleks-grafanaslo/spire-grafana-slo/spire-regelrett:$(date +%Y%m%d-%H%M)-$(git rev-parse --short=7 HEAD)
docker buildx build --platform linux/amd64 -t "$IMG" --push .
docker buildx imagetools inspect "$IMG" --format '{{.Image.Platform}}'
```

## Database

Volumet slettes bare sammen med namespacet:

```bash
kubectl delete namespace ns-regelrett-db
```

psql som superbruker:

```bash
kubectl exec -it -n ns-regelrett-db statefulset-regelrett-postgres-0 -- psql -U postgres -d regelrett
```

Init-SQL-en kjører bare første gang volumet initialiseres. Endres `APP_DB_PASSWORD` senere,
må det også endres i databasen:

```sql
ALTER ROLE regelrett WITH PASSWORD 'nytt-passord';
```

Flyway kjører skjemaendringer ved oppstart, fra `src/main/resources/db/migration`.

## NetworkPolicy

Begge namespacene avviser all innkommende trafikk, med disse unntakene:

- `ns-regelrett` slipper inn Backstage (`app.kubernetes.io/name: backstage` i `ns-backstage`)
  og Googles lastbalanserer (`35.191.0.0/16`, `130.211.0.0/22`).
- `ns-regelrett-db` slipper inn appen på port 5432.

Reglene i `ns-regelrett` bruker pod-porten 8080, ikke Service-porten 80. NetworkPolicy
evalueres etter at trafikken er NAT'et til poden, så port 80 ville ikke matchet noe.

Alle reglene matcher på podlabelen `app.kubernetes.io/name: spire-regelrett`. Endres den i
`deployment.yaml`, må `networkpolicy.yaml` og `db/networkpolicy.yaml` endres samtidig. Ellers
droppes trafikken, og det viser seg som timeout (`SocketTimeoutException` mot databasen), ikke
«connection refused».

## Kjent svakhet

`db/postgres.yaml` setter `POSTGRES_HOST_AUTH_METHOD: trust`, så databasen godtar tilkoblinger
uten passord. `db/networkpolicy.yaml` er det eneste som skjermer den.
