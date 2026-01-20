# Designer Operator

The Designer Operator is a Kubernetes Operator built by Global Maksimum to manage cloud-based IDE environments (Theia). It is designed to replace the legacy theia-cloud-operator within the Henkan cluster.

This operator manages the lifecycle of persistent workspaces and ephemeral coding sessions, handling authentication (via Keycloak/OAuth2-Proxy), storage provisioning, and dynamic ingress routing.

## Tech Stack & Background

This project was built to replace an existing operator based on theia-cloud. While it retains similar architectural concepts, it is a complete rewrite using modern Java/Kotlin tooling for better maintainability and control.

**Core Technologies:**
- **Language:** Kotlin (JVM 21)
- **Framework:** Java Operator SDK (JOSDK) v5+
- **Kubernetes Client:** Fabric8
- **Templating:** Apache Velocity Engine (for .vm template rendering)
- **Build Tool:** Gradle (with ShadowJar plugin)

## Architecture & Custom Resources

The operator introduces three Custom Resource Definitions (CRDs) to the cluster.

### 1. AppDefinition (appdefinitions.theia.cloud)

The blueprint for the IDE. It defines the Docker image, resource limits, network policies, and timeouts.

**Behavior:** The operator watches this resource to ensure the referenced Shared Ingress exists and adds a non-controlling OwnerReference to track dependencies.

### 2. Workspace (workspaces.theia.cloud)

Represents the user's persistent state (1:1 with a PVC).

**Behavior:** Calculates deterministic PVC names, creates PVCs using pvc.yaml.vm, waits for binding, and syncs Henkan UI labels.

### 3. Session (sessions.theia.cloud)

Represents a running IDE instance.

**Behavior:** Validates storage, generates a Deployment (Theia + OAuth2-Proxy), Service, ConfigMaps, and patches the Shared Ingress to route traffic.

## Configuration Strategy

Configuration is strictly **Argument-Driven**. The operator does not read from a local config file or environment variables directly. Instead, it relies on a custom CliConfigParser.

**How it works:**
1. **Entry Point:** When the operator starts (main.kt), it accepts command-line arguments (e.g., --keycloakURL ...).
2. **Parsing:** The CliConfigParser reads these arguments and maps them to the OperatorConfig data class.
3. **Validation:** Required flags (like Keycloak settings) are validated immediately. If missing, the operator fails to start.
4. **Injection:** The OperatorConfig object is passed into the Reconcilers, making configuration immutable during runtime.

### CLI Arguments

| Flag | Default | Description |
|------|---------|-------------|
| --keycloakURL | Required | Base URL of the Keycloak instance. |
| --keycloakRealm | Required | The Keycloak Realm name. |
| --keycloakClientId | Required | The Client ID for OIDC. |
| --appId | null | Application ID override. |
| --ingressScheme | https | Scheme for generated URLs. |
| --instancesHost | theia.localtest.me | The generic host where sessions are exposed. |
| --oAuth2ProxyVersion | quay.io/... | The Docker image for the auth sidecar. |
| --storageClassName | null | K8s StorageClass for Workspace PVCs. |
| --requestedStorage | null | Size of Workspace PVCs (e.g., 1Gi). |
| --sessionsPerUser | null | Max concurrent sessions allowed per user. |

## Project Structure

The codebase is organized by logical domain (Controllers/Reconcilers) rather than technical layers.

```
src/main/kotlin/com/globalmaksimum/designeroperator
├── Main.kt                      # Entry point: Configures Client, registers Controllers
├── SessionTimeoutReaper.kt      # Background thread to clean up timed-out sessions
│
├── config/
│   ├── CliConfigParser.kt       # Parses CLI args into Config object
│   └── OperatorConfig.kt        # Data class holding runtime configuration
│
├── naming/                      # Logic for deterministic naming
│   ├── Labeling.kt              # Sanitizes strings for K8s labels
│   ├── SessionNaming.kt         # Naming rules for Session resources
│   └── WorkspaceNaming.kt       # Naming rules for PVCs and Workspaces
│
├── reconcilers/                 # The core logic (Controllers)
│   ├── appdefinition/
│   │   └── AppDefinitionReconciler.kt
│   ├── session/
│   │   ├── SessionReconciler.kt # Main Session logic
│   │   ├── SessionResources.kt  # Generates Deployments/Services/ConfigMaps
│   │   └── SessionIngress.kt    # Logic to patch Shared Ingress
│   └── workspace/
│       ├── WorkspaceReconciler.kt
│       └── WorkspaceResources.kt # Handles PVC creation/binding
│
└── utils/
    ├── OwnerRefs.kt             # Helper to build OwnerReferences
    └── TemplateRenderer.kt      # Apache Velocity wrapper
```

**Resources:**

```
src/main/resources/templates/
├── deployment.yaml.vm           # Theia + OAuth2 Proxy Deployment template
├── service.yaml.vm              # ClusterIP Service template
└── pvc.yaml.vm                  # PersistentVolumeClaim template
```

## Runtime Context: Local Development vs Production (Henkan)

### Running Locally Without henkan-server

The operator can be tested locally and independently without the full Henkan platform.

For local development:
- Apply the CRDs from k8s/crds
- Apply sample resources from k8s/samples
- Run the operator locally or in-cluster

These sample manifests simulate the behavior of henkan-server by directly creating AppDefinition, Workspace, and Session resources.

### Role of henkan-server in Production

In the real Henkan environment, CRDs are created and managed by **henkan-server** (a long-running Scala service). henkan-server is responsible for:
- Creating and deleting Workspace and Session CR instances
- Setting labels and metadata on CRs
- Acting as the user-facing API (triggered from the Henkan Designer UI)

The Designer Operator does not override or mutate existing labels. All labels provided on CRs are treated as authoritative input. This separation is intentional: henkan-server owns intent, and the operator owns reconciliation.

## Local Testing & Development

The operator can be tested both locally (out-of-cluster) and inside a Kubernetes cluster.

### 1. Preparing the Cluster (CRDs & Base Resources)

**Apply CRDs:**

```bash
kubectl apply -f k8s/crds/appdefinition-crd.yaml
kubectl apply -f k8s/crds/workspace-crd.yaml
kubectl apply -f k8s/crds/session-crd.yaml
```

**Apply required base resources:**

```bash
kubectl apply -f k8s/samples/rbac.yaml
kubectl apply -f k8s/samples/oauth2-templates.yaml
kubectl apply -f k8s/samples/oauth2-velocity-template.yaml
```


### 2A. Running the Operator Locally (Out-of-Cluster)

This mode is useful for fast iteration and debugging without rebuilding Docker images.

**Build and run:**

```bash
./gradlew build

./gradlew run --args="\
  --keycloakURL http://keycloak.172.12.2.32.sslip.io/auth/ \
  --keycloakRealm henkan \
  --keycloakClientId henkan-designer \
  --instancesHost theia.localtest.me \
  --ingressScheme http \
  --appId henkan \
  --oAuth2ProxyVersion quay.io/oauth2-proxy/oauth2-proxy:v7.5.1 \
  --storageClassName hostpath \
  --requestedStorage 250Mi \
"
```

⚠️ If you don't have access to a shared Keycloak instance, you can start a local Keycloak Docker container for testing. Update --keycloakURL accordingly.

### 2B. Running the Operator In-Cluster

This method deploys the operator as a standard Pod using k8s/samples/operator-deployment.yaml.

#### Understanding operator-deployment.yaml

This sample file contains the operational configuration for the operator.

- **image:** Points to the Docker image you built. If using a local cluster (Kind/Minikube), ensure the image is loaded.
- **args:** These are the same flags used in the local run command. You must edit these values (e.g., --keycloakURL) to match your actual environment variables or services.
- **serviceAccountName:** Uses operator-api-service-account (defined in rbac.yaml) to give the Pod permission to list/watch CRDs.

#### Deployment Steps

**Build the Image:**

```bash
docker build -t my-operator:1.0.0 .
```

**Load the Image (If using Kind/Minikube):**

```bash
# For Kind
kind load docker-image my-operator:1.0.0

# For Minikube
minikube image load my-operator:1.0.0
```

**Apply the Deployment:**

Ensure you have edited k8s/samples/operator-deployment.yaml with your correct parameters first.

```bash
kubectl apply -f k8s/samples/operator-deployment.yaml
```

**Restart (If updating code):**

If you rebuild the image with the same tag, force a restart to pull the new code:

```bash
kubectl rollout restart deployment/operator-deployment
```

---

### 3. Applying Sample Resources (Simulating henkan-server)

The following manifests simulate what henkan-server normally creates in production.

**Reset previous test state (optional but recommended):**

```bash
kubectl delete appdefinition henkan-designer || true
kubectl delete ingress henkan-designer-ingress || true
kubectl delete workspace bartu-demo-ws || true
kubectl delete session bartu-demo-ws || true
```

**Apply samples:**

```bash
kubectl apply -f k8s/samples/ingress-sample.yaml
kubectl apply -f k8s/samples/appdefinition-sample.yaml
kubectl apply -f k8s/samples/workspace-sample.yaml
kubectl apply -f k8s/samples/session-sample.yaml
```

### 4. Accessing the Theia IDE (OAuth2 Redirect)

After the Session is created, retrieve its UID:

```bash
SESSION_UID=$(kubectl get session bartu-demo-ws -o jsonpath='{.metadata.uid}')
```

Open the following URL:

```
https://theia.localtest.me/${SESSION_UID}/?rd=/${SESSION_UID}/
```

⚠️ The rd parameter is mandatory in local testing. In production, this parameter is automatically added by henkan-server.

---

Copyright © Global Maksimum