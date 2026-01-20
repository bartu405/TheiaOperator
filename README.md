# Designer Operator

The **Designer Operator** is a Kubernetes Operator built by Global Maksimum to manage cloud-based IDE environments (Theia) in the Henkan platform.

It replaces the legacy `theia-cloud-operator` with a complete rewrite using modern Kotlin/JVM tooling, focusing on **clear ownership boundaries**, **predictable reconciliation**, and **production-safe behavior**.


---

## Core Technologies

* **Language:** Kotlin (JVM 21)
* **Operator Framework:** Java Operator SDK (v5+)
* **Kubernetes Client:** Fabric8
* **Templating:** Apache Velocity (`.vm`)
* **Build:** Gradle + ShadowJar

---

## Architecture & Custom Resources

The operator introduces **three CRDs**, each with a clearly scoped responsibility.

### AppDefinition (`appdefinitions.theia.cloud`)

Defines *what* an IDE looks like:

* Image
* Ports
* Resource limits
* Monitoring & timeouts
* Shared Ingress name

**Operator behavior**

* Validates spec
* Verifies the shared Ingress exists
* Adds a **non-controller OwnerReference** to track dependency
* Does **not** create or manage the Ingress

---

### Workspace (`workspaces.theia.cloud`)

Represents a user’s **persistent state**.

**Operator behavior**

* Calculates a deterministic PVC name
* Creates the PVC using `pvc.yaml.vm`
* Waits until the PVC is `Bound`
* Syncs Henkan UI labels
* Owns the PVC via controller reference

---

### Session (`sessions.theia.cloud`)

Represents a **running IDE instance**.

**Operator behavior**

* Enforces constraints:

    * one session per workspace
    * optional max sessions per user
* Waits for workspace storage to be ready
* Creates:

    * Deployment (Theia + OAuth2 proxy)
    * Service
    * ConfigMaps
* Adds a session-specific path to the shared Ingress
* Cleans up Ingress paths on deletion via a finalizer

⚠️ **Design choice:**
Session Deployments are treated as **immutable**.
Changes require deleting and recreating the Session resource.

---

## Configuration Model

The operator is **strictly argument-driven**.

All runtime configuration is provided via CLI flags and parsed by `CliConfigParser`.

### Supported CLI Arguments

| Flag                   | Description                   |
| ---------------------- | ----------------------------- |
| `--keycloakURL`        | Base Keycloak URL             |
| `--keycloakRealm`      | Keycloak Realm                |
| `--keycloakClientId`   | OIDC Client ID                |
| `--appId`              | Application ID override       |
| `--ingressScheme`      | URL scheme (`http` / `https`) |
| `--instancesHost`      | Public host for sessions      |
| `--oAuth2ProxyVersion` | OAuth2 proxy image            |
| `--storageClassName`   | StorageClass for PVCs         |
| `--requestedStorage`   | PVC size (e.g. `250Mi`)       |
| `--sessionsPerUser`    | Max concurrent sessions       |

---


## Assumptions

The Designer Operator intentionally **does not create certain resources**.
These are assumed to be **pre-installed**, typically via Helm.

The operator will fail or wait indefinitely if they are missing.

### Resources NOT created by the operator

- **Shared Ingress**
    - Must already exist in the namespace
    - Usually created by Helm
    - The operator only adds/removes session paths

- **AppDefinition resources**
    - Must already exist in the namespace
    - Usually created by Helm
    - The operator never generates AppDefinitions

- **OAuth2 Velocity template ConfigMap**
    - `oauth2-velocity-template`
    - Must exist before creating Sessions
    - Typically installed via Helm

- **RBAC and ServiceAccounts**
    - Required permissions are assumed to exist
    - Sample manifests are provided under `k8s/samples/`

This separation is intentional and keeps the operator focused on
**reconciliation**, not **platform bootstrapping**.


## Runtime Context: Local vs Production

### Production (Henkan)

In production, **henkan-server** is responsible for:

* Creating and deleting Workspace and Session CRs
* Acting as the user-facing API
* Setting labels and metadata

The Designer Operator:

* Treats CR labels as **authoritative**
* Does not override henkan-server intent
* Only reconciles infrastructure state


> **Important:**  
> The Designer Operator does not know *who* created a Custom Resource.  
> It reacts the same way whether a CR was created by `henkan-server` (production)
> or manually via `kubectl` (local development).




---

### Option A (Recommended): Run Locally, Connect to Cluster

Best for **fast iteration and debugging**.

#### 1. Prepare the cluster

```bash
kubectl apply -f k8s/crds/
kubectl apply -f k8s/samples/rbac.yaml
kubectl apply -f k8s/samples/oauth2-templates.yaml
kubectl apply -f k8s/samples/oauth2-velocity-template.yaml
```

#### 2. Run the operator locally

```bash
./gradlew run --args="\
  --keycloakURL https://keycloak.172.12.2.32.sslip.io/auth/ \
  --keycloakRealm henkan \
  --keycloakClientId henkan-designer \
  --instancesHost theia.localtest.me \
  --ingressScheme http \
  --appId henkan \
  --storageClassName hostpath \
  --requestedStorage 250Mi \
"
```

> 💡 You can run a local Keycloak container if needed.

---

### Option B: Run In-Cluster (Production-like)



#### Build and load image

```bash
docker build -t my-operator:1.0.0 .
kind load docker-image my-operator:1.0.0
```

#### Deploy operator

Edit `k8s/samples/operator-deployment.yaml` to match your environment, then:

```bash
kubectl apply -f k8s/samples/operator-deployment.yaml
```

To reload code with the same tag:

```bash
kubectl rollout restart deployment/operator-deployment
```

---

## Simulating `henkan-server`

To test end-to-end behavior locally, apply sample CRs manually.

```bash
kubectl apply -f k8s/samples/ingress-sample.yaml
kubectl apply -f k8s/samples/appdefinition-sample.yaml
kubectl apply -f k8s/samples/workspace-sample.yaml
kubectl apply -f k8s/samples/session-sample.yaml
```

---

## Accessing the IDE

Retrieve the Session UID:

```bash
SESSION_UID=$(kubectl get session bartu-demo-ws -o jsonpath='{.metadata.uid}')
```

Open:

```
https://theia.localtest.me/${SESSION_UID}/?rd=/${SESSION_UID}/
```

⚠️ The `rd` parameter is required for local testing.
In production, it is added automatically by `henkan-server`.

---
