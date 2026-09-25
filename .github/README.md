# CI/CD

Enhver push til `main` deployer Regelrett til GCP.

Klyngen, databasen og Secretene står i [k8s/README.md](../k8s/README.md). Denne filen
dekker bare det GitHub Actions gjør.

## Hva som skjer når

| Hendelse | Workflow | Jobb | Gjør |
| --- | --- | --- | --- |
| Pull request mot `main` | [`pr-build.yml`](workflows/pr-build.yml) | `build` | `./gradlew test`, `pnpm test`, bygger imaget uten å publisere det, og validerer manifestene |
| Push til `main` | [`deploy-main.yml`](workflows/deploy-main.yml) | `deploy` | Bygger og publiserer imaget, og deployer `k8s/*.yaml` til `ns-regelrett` |
| `workflow_dispatch` med `tag` | [`deploy-main.yml`](workflows/deploy-main.yml) | `deploy` | Deployer en tag som alt er publisert, uten å bygge |

`build` er required status check på `main`. PR-en kan ikke merges før den er grønn.

Deployen hopper over rene dokumentasjonsendringer (`paths-ignore: '**/*.md'`). Ligger det
kode i samme commit, kjører den som vanlig. `pr-build.yml` har bevisst **ingen**
`paths-ignore`: en required status check som hoppes over rapporterer aldri, og PR-en blir
stående i «Expected — Waiting for status» for alltid.

Image-taggen er en kortere versjon av commitens SHA. Det avløser
`<dato>-<tid>-<sha>`-formatet som ble brukt ved manuell deploy, slik at hver tag peker
entydig på én commit.

## Ingen kustomize

Manifestene er rene YAML-filer. Deployen slår dem sammen med `awk` og bytter ut
plassholderen for imaget:

```bash
awk 'FNR==1 && NR>1 {print "---"} {print}' k8s/*.yaml \
  | sed "s|replaced-at-deploy|<image>|g" \
  | kubectl apply -f -
```

`awk` og ikke `cat`: flere av manifestfilene mangler avsluttende linjeskift, og et bart
`cat` ville limt `---` på siste linje i fila før. `awk`s `print` normaliserer det.

Bare filene rett under `k8s/` deployes. `k8s/db/` har egen livssyklus og røres ikke.

## Ingen Secrets i GitHub

Deployen trenger ingen hemmeligheter. Secretene lages én gang i klyngen med `kubectl`
(se [k8s/README.md](../k8s/README.md)), og manifestene refererer dem bare ved navn —
`secret-regelrett-db`, `secret-regelrett-oauth` og `secret-regelrett-schemasources`.
Verdiene passerer aldri gjennom GitHub.

Endres en Secret, må poddene startes på nytt for å lese den:

```bash
kubectl rollout restart deployment/deployment-regelrett -n ns-regelrett
```

## Autentisering

Workflowen har ingen nøkkelfil. Den henter et OIDC-token fra GitHub og bytter det inn i et
kortlevd Google-token gjennom Workload Identity Federation.

```sh
.github/setup-wif.sh
```

Kjøres én gang, av noen med `roles/owner`. Idempotent, så en gjenkjøring er trygg.
Prosjekt, region og registry-repo leses fra `gcp-config.yaml`, og GitHub-repoet utledes av
git-remoten — så attributt-betingelsen kan ikke skrives feil for hånd.

Den oppretter:

| Ressurs | Navn | Delt? |
| --- | --- | --- |
| Workload identity pool | `github` | ja, med andre repoer i prosjektet |
| OIDC-provider | `regelrett-oidc` | nei, låst til dette repoet |
| Service-konto | `sa-github-deploy-regelrett` | nei |
| Rolle på registry-repoet | `roles/artifactregistry.writer` | – |
| Rolle på prosjektet | `roles/container.developer` | – |

Egen provider og egen service-konto, selv om poolen deles: da kan et annet repo i samme
prosjekt aldri låne Regelretts identitet, og oppsettet her kan endres uten å røre andres.

Attributt-betingelsen (`assertion.repository == '<eier>/<repo>'`) er den viktigste linjen i
hele oppsettet. Uten den kunne en hvilken som helst GitHub-workflow i verden hente et token
mot prosjektet. Det er derfor skriptet kontrollerer betingelsen på en gjenkjøring i stedet
for å nøye seg med at provideren finnes.

`roles/container.developer` gir tilgang til Kubernetes-objektene, men **ikke** rett til å
opprette eller slette klynger. En deploy-identitet skal ikke kunne rive klyngen.

## Variabler

Alt ligger på environment `gcp-regelrett-poc` (Settings → Environments). Environmentet gir også
deploy-historikk og et sted å skru på required reviewers senere.

| Navn | Verdi |
| --- | --- |
| `GCP_PROJECT_ID` | `gcp-fleks-grafanaslo` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/<nummer>/locations/global/workloadIdentityPools/github/providers/regelrett-oidc` |
| `GCP_SERVICE_ACCOUNT` | `sa-github-deploy-regelrett@gcp-fleks-grafanaslo.iam.gserviceaccount.com` |

Alle tre er *variables*, ikke secrets. Region, klynge, namespace og deployment-navn står
ikke i GitHub — de leses fra `gcp-config.yaml`.

`setup-wif.sh` skriver ut ferdige `gh variable set`-kommandoer til slutt.

## Rollback

**1. Revert og merge — normalveien.**

```sh
git revert <sha>
```

Historikken blir riktig, og `main` fortsetter å beskrive det som kjører.

**2. Deploy en tidligere tag — raskest.**

```sh
gh workflow run deploy-main.yml -f tag=<tidligere-korte-sha>
```

Imaget er allerede publisert, så byggestegene hoppes over. Det deployer nøyaktig de bytene
som kjørte før, ikke et gjenbygg som kan resolve avhengigheter annerledes. Merk at **bare
imaget** rulles tilbake — manifestene hentes fra `main` slik den står nå.

Taggene som finnes:

```sh
gh run list --workflow deploy-main.yml --json headSha,conclusion,createdAt --limit 20
```

**3. `rollout undo` — nødbremsen.**

```sh
kubectl -n ns-regelrett rollout undo deployment/deployment-regelrett
```

Raskest, men klyngen er nå ute av takt med `main`, og neste merge overskriver den stille.
Følg alltid opp med vei 1.

## Hva CI ikke gjør

| Oppgave | Hvorfor ikke |
| --- | --- |
| Opprette klynge og Artifact Registry | Engangsoppsett, delt med andre apper i prosjektet. Ville krevd at deploy-identiteten kunne opprette og konfigurere om infrastruktur |
| Deploye `k8s/db/` | Databasen har egen livssyklus, og init-SQL-en kjører bare ved første initialisering. En automatisk apply på hver merge ville skjult at den ikke gjør det folk tror |
| Lage eller rotere Secrets | Verdiene skal ikke passere gjennom GitHub. Se [k8s/README.md](../k8s/README.md) |
| Slette namespace eller klynge | Destruktivt og uten backup. `container.developer` gir ikke SA-en rett til å slette klyngen |

## Teste en endring i workflowen

`workflow_dispatch` hjelper ikke før filen ligger på `main`. Legg derfor inn en midlertidig
trigger mens du jobber:

```yaml
on:
  push:
    branches: [main, 'ci/**']     # ← fjernes i siste commit før merge
```

**Ikke skriv rendret YAML til stdout** hvis manifestene en dag skulle inneholde Secrets.
I dag gjør de ikke det — Secretene lages utenfor — men deploy-steget piper likevel rett inn
i `kubectl apply -f -`, og bør fortsette med det.