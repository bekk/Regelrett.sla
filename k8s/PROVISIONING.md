# Skjemakilder (schemasources) i Kubernetes



### 1. Fyll inn en ekte schemasource-fil lokalt

```bash
cp conf/provisioning/schemasources/sample.yaml conf/provisioning/schemasources/regelrett.yaml
```

### 2. Lag Secreten på klyngen

```bash
kubectl create secret generic secret-regelrett-schemasources \
  -n ns-regelrett \
  --from-file=regelrett.yaml=conf/provisioning/schemasources/regelrett.yaml \
  --dry-run=client -o yaml | kubectl apply -f -
```

`--dry-run=client -o yaml | kubectl apply -f -` gjør at kommandoen kan kjøres på nytt
uten `AlreadyExists`-feil når filen endres senere.

### 3. Monter Secreten i deployment.yaml

Legg til i `k8s/deployment.yaml`, under den eksisterende `tmp`-mounten:

```yaml
          volumeMounts:
            - name: tmp
              mountPath: /tmp
            - name: schemasources
              mountPath: /etc/regelrett/provisioning/schemasources
              readOnly: true
```

og en tilhørende volume-definisjon:

```yaml
      volumes:
        - name: tmp
          emptyDir: {}
        - name: schemasources
          secret:
            secretName: secret-regelrett-schemasources
```

`readOnlyRootFilesystem: true` (allerede satt) er ikke til hinder — Secret-volumer
monteres uavhengig av dette.

### 4. Rull ut endringen

```bash
kubectl apply -f k8s/deployment.yaml
kubectl rollout status deployment/deployment-regelrett -n ns-regelrett --timeout=5m
```

### 5. Verifiser

```bash
kubectl port-forward -n ns-regelrett service/service-regelrett 8080:80
```