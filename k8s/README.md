# Regelrett i Kubernetes

Rene manifester, applyet med `kubectl apply -f`. Ingen kustomize, ingen Helm.

Alt utenom Secrets ligger som YAML her. Secrets lages med `kubectl` fra env-filer
som er gitignorert — se [Secrets](#secrets) under.

## Oversikt

| Fil | Objekt | Namespace |
| --- | --- | --- |
| `namespace.yaml` | Namespace `ns-regelrett` | – |
| `configmap.yaml` | ConfigMap `config-regelrett` | `ns-regelrett` |
| `deployment.yaml` | Deployment `deployment-regelrett` | `ns-regelrett` |
| `service.yaml` | Service `service-regelrett` | `ns-regelrett` |
| `db/namespace.yaml` | Namespace `ns-regelrett-db` | – |
| `db/configmap-initdb.yaml` | ConfigMap `config-postgres-initdb` | `ns-regelrett-db` |
| `db/postgres.yaml` | Service + StatefulSet | `ns-regelrett-db` |

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
kubectl apply -f k8s/db/configmap-initdb.yaml -f k8s/db/postgres.yaml
kubectl rollout status statefulset/statefulset-regelrett-postgres -n ns-regelrett-db --timeout=5m
```

Så appen:

```bash
kubectl apply -f k8s/configmap.yaml -f k8s/service.yaml -f k8s/deployment.yaml
```

### Image

`deployment.yaml` har `image: replaced-at-deploy`. Ingenting i dette repoet erstatter den —
GitOps-flyten i `.github/workflows/build-deploy.yml` skriver image-URL-en til repoet
`kartverket/skvis-apps`. Applyer du manifestene direkte, går podden i `ImagePullBackOff`
til du setter et image selv:

```bash
kubectl set image deployment/deployment-regelrett spire-regelrett=<image> -n ns-regelrett
```

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
tilkoblinger uten passord. Det finnes ingen NetworkPolicy i dette repoet, så databasen er
åpen for alt som kjører i klyngen. Hører til det gjenstående oppsettsarbeidet.
