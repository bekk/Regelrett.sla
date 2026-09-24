# Regelrett i Kubernetes

Rene manifester, applyet med `kubectl apply -f`. Ingen kustomize, ingen Helm.

**Merge til `main` deployer appen automatisk.** Kommandoene under er engangsoppsettet og
feilsøkingsveien — se [.github/README.md](../.github/README.md) for CI-flyten.

Alt utenom Secrets ligger som YAML her. Secrets lages med `kubectl` fra env-filer
som er gitignorert — se [Secrets](#secrets) under.

## Oversikt

| Fil | Objekt | Namespace |
| --- | --- | --- |
| `namespace.yaml` | Namespace `ns-regelrett` | – |
| `configmap.yaml` | ConfigMap `config-regelrett` | `ns-regelrett` |
| `deployment.yaml` | Deployment `deployment-regelrett` | `ns-regelrett` |
| `service.yaml` | Service `service-regelrett` | `ns-regelrett` |
| `networkpolicy.yaml` | NetworkPolicy `netpol-default-deny-ingress` | `ns-regelrett` |
| `db/namespace.yaml` | Namespace `ns-regelrett-db` | – |
| `db/configmap-initdb.yaml` | ConfigMap `config-postgres-initdb` | `ns-regelrett-db` |
| `db/postgres.yaml` | Service + StatefulSet | `ns-regelrett-db` |
| `db/networkpolicy.yaml` | NetworkPolicy default-deny + allow fra appen | `ns-regelrett-db` |

Databasen bor i et eget namespace med vilje. Rives appen — `kubectl delete namespace
ns-regelrett` er den korteste veien — følger ikke dataene med.

## Secrets

Tre Secrets må finnes før poddene starter. Uten dem blir de stående i
`CreateContainerConfigError`.

Databasen og appen har hver sin Secret: databasen trenger superbrukerpassordet, appen
trenger bare sin egen innlogging.

| Secret | Namespace | Nøkler | Lest av |
| --- | --- | --- | --- |
| `secret-regelrett-postgres` | `ns-regelrett-db` | `POSTGRES_PASSWORD`, `APP_DB_PASSWORD` | postgres-imaget og init-SQL-en |
| `secret-regelrett-db` | `ns-regelrett` | `RR_DATABASE_USER`, `RR_DATABASE_PASSWORD` | appen, via `envFrom` |
| `secret-regelrett-oauth` | `ns-regelrett` | `RR_OAUTH_*`, `RR_AIRTABLE_ACCESS_TOKEN` | appen, via `envFrom` |

### Fyll inn env-filene

```bash
cp k8s/secrets/postgres.env.example k8s/secrets/postgres.env
cp k8s/secrets/oauth.env.example k8s/secrets/oauth.env
```

Begge `.env`-filene er gitignorert. Generer passord som ikke trenger siteres noe sted:

```bash
openssl rand -hex 24
```

To filer og ikke én, fordi `--from-env-file` tar *alle* nøklene i fila. Med én felles fil
ville OAuth-hemmelighetene havnet i databasens namespace også.

### Lag Secretene

Namespacene må finnes først — se [Deploy](#deploy).

```bash
kubectl create secret generic secret-regelrett-postgres -n ns-regelrett-db --from-env-file=k8s/secrets/postgres.env --dry-run=client -o yaml | kubectl apply -f -
```

Appens side henter passordet fra **samme fil**, så de to ikke kan gå i utakt.
`RR_DATABASE_USER` er `regelrett` fordi init-SQL-en hardkoder rollenavnet:

```bash
set -a && . ./k8s/secrets/postgres.env && set +a && kubectl create secret generic secret-regelrett-db -n ns-regelrett --from-literal=RR_DATABASE_USER=regelrett --from-literal=RR_DATABASE_PASSWORD="$APP_DB_PASSWORD" --dry-run=client -o yaml | kubectl apply -f -
```

```bash
kubectl create secret generic secret-regelrett-oauth -n ns-regelrett --from-env-file=k8s/secrets/oauth.env --dry-run=client -o yaml | kubectl apply -f -
```

`--dry-run=client -o yaml | kubectl apply -f -` i stedet for `kubectl create secret` alene:
da feiler ikke kommandoen med `AlreadyExists` når den kjøres på nytt, og verdiene
oppdateres i stedet.

En pod leser `envFrom` **én gang, ved oppstart**, og merker ingenting til at Secreten bak
er endret. Etter en endring:

```bash
kubectl rollout restart deployment/deployment-regelrett -n ns-regelrett
```

## Deploy

Rekkefølgen betyr noe: namespacene må finnes før Secretene, og både Secreten og
initdb-ConfigMapen før StatefulSetet kan starte.

```bash
kubectl apply -f k8s/namespace.yaml -f k8s/db/namespace.yaml
```

Deretter de tre Secret-kommandoene over. Så databasen:

```bash
kubectl apply -f k8s/db/configmap-initdb.yaml -f k8s/db/postgres.yaml -f k8s/db/networkpolicy.yaml
kubectl rollout status statefulset/statefulset-regelrett-postgres -n ns-regelrett-db --timeout=5m
```

Så appen. Normalt gjør GitHub Actions dette ved merge til `main` — se
[.github/README.md](../.github/README.md). For hånd, med plassholderen byttet ut:

```bash
IMG=europe-north1-docker.pkg.dev/gcp-fleks-grafanaslo/spire-grafana-slo/spire-regelrett:<tag>
awk 'FNR==1 && NR>1 {print "---"} {print}' k8s/*.yaml \
  | sed "s|replaced-at-deploy|$IMG|g" \
  | kubectl apply -f -
```

Deployer du slik mens CI er i bruk, blir det du la ut overskrevet av neste merge.

### Image

Image-en ligger i Artifact Registry i `gcp-fleks-grafanaslo`:

```
europe-north1-docker.pkg.dev/gcp-fleks-grafanaslo/spire-grafana-slo/spire-regelrett
```

**Image-referansen må være fullt kvalifisert.** Et bart navn som
`spire-regelrett:<tag>` tolkes av Kubernetes som `docker.io/library/spire-regelrett:<tag>`,
og podden går i `ErrImagePull`/`ImagePullBackOff` med `not found` — selv om tagen finnes i
Artifact Registry. Registry-host, prosjekt og repo må stå i referansen:

```bash
kubectl set image deployment/deployment-regelrett spire-regelrett=europe-north1-docker.pkg.dev/gcp-fleks-grafanaslo/spire-grafana-slo/spire-regelrett:<tag> -n ns-regelrett
```

Tilgjengelige tagger:

```bash
gcloud artifacts docker images list europe-north1-docker.pkg.dev/gcp-fleks-grafanaslo/spire-grafana-slo/spire-regelrett --include-tags
```

### Bygg image selv

Nodene i klyngen er `amd64`. Bygger du på en Apple Silicon-Mac uten `--platform`, får du
et `arm64`-image som pulles helt fint, men krasjer i `CrashLoopBackOff` med
`exec /bin/sh: exec format error`. `JS_PLATFORM` i Dockerfilen pinner bare JS-steget —
sluttimaget arver arkitekturen til maskinen som bygger.

```bash
gcloud auth configure-docker europe-north1-docker.pkg.dev
IMG=europe-north1-docker.pkg.dev/gcp-fleks-grafanaslo/spire-grafana-slo/spire-regelrett:$(date +%Y%m%d-%H%M)-$(git rev-parse --short=7 HEAD)
docker buildx build --platform linux/amd64 -t "$IMG" --push .
```

Sjekk arkitekturen til et image før du deployer det:

```bash
docker buildx imagetools inspect "$IMG" --format '{{.Image.Platform}}'
```

`deployment.yaml` inneholder ikke en tag, men plassholderen `replaced-at-deploy`. Deployen
bytter den ut med gjeldende image:

```bash
awk 'FNR==1 && NR>1 {print "---"} {print}' k8s/*.yaml \
  | sed "s|replaced-at-deploy|$IMG|g" \
  | kubectl apply -f -
```

Et bart `kubectl apply -f k8s/deployment.yaml` vil derfor feile på plassholderen. Det er
med vilje: alternativet var at manifestet inneholdt en tag som stille ble eldre for hver
deploy, og at en apply for hånd rullet klyngen tilbake uten at noen merket det.

### Nå appen

Det er ingen Ingress. `RR_SERVER_ROOT_URL` i `configmap.yaml` er satt til
`http://localhost:8080`, som passer til:

```bash
kubectl port-forward -n ns-regelrett service/service-regelrett 8080:80
```

Skal Regelrett nås på et domene, må `root_url` endres **og** den nye redirect-URI-en
(`${root_url}/callback`) registreres på appregistreringen i Entra ID.

## Database

Volumet overlever at appen rives. Det er sletting av namespacet som fjerner det:

```bash
kubectl delete namespace ns-regelrett-db
```

psql som superbruker:

```bash
kubectl exec -it -n ns-regelrett-db statefulset-regelrett-postgres-0 -- psql -U postgres -d regelrett
```

Init-SQL-en i `db/configmap-initdb.yaml` kjører **bare** første gang volumet
initialiseres. Endres `APP_DB_PASSWORD` etter det, må endringen gjøres i databasen også:

```sql
ALTER ROLE regelrett WITH PASSWORD 'nytt-passord';
```

Skjemaendringer kjøres av Flyway ved oppstart, fra `src/main/resources/db/migration`.

## Kjent svakhet

`db/postgres.yaml` setter `POSTGRES_HOST_AUTH_METHOD: trust`, som slipper inn alle
tilkoblinger uten passord. `db/networkpolicy.yaml` er det eneste som skjermer databasen:
default-deny på ingress, pluss én regel som slipper inn app-poddene på port 5432.

Den regelen matcher på podLabelen `app.kubernetes.io/name: spire-regelrett`. Endres labelen
i `deployment.yaml` uten at `db/networkpolicy.yaml` endres i samme slengen, blir trafikken
droppet av default-deny, og appen feiler med `SocketTimeoutException: Connect timed out`
mot databasen — ikke «connection refused». De to filene må holdes i sync.
