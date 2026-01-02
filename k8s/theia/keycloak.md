# Complete Keycloak OAuth2 Testing Guide

## Overview

This guide will help you test the complete OAuth2/Keycloak authentication flow in your operator.

**The Authentication Flow:**
```
User Request
    ↓
http://theia.localtest.me:8080/{session-uid}/
    ↓
Ingress (nginx) - routes based on path
    ↓
Service (port 5000)
    ↓
OAuth2-Proxy Sidecar (port 5000)
    ├─ Not authenticated? → Redirect to Keycloak login
    └─ Authenticated? → Proxy to Theia app (port 3000)
         ↓
Keycloak Login (http://192.168.1.56:8080/auth/realms/henkan)
    ↓
User enters: bartu / bartu
    ↓
Keycloak validates and redirects back
    ↓
OAuth2-Proxy validates token, sets cookie
    ↓
Request proxied to Theia container
```

---

## Part 1: Verify Keycloak Configuration

### 1.1 Access Keycloak Admin Console

```bash
# Open in browser:
http://192.168.1.56:8080/auth/admin

# Login with:
Username: admin
Password: admin
```

### 1.2 Verify Realm Configuration

1. **Select Realm**: Click dropdown (top left) → Select `henkan`

2. **Realm Settings**:
    - Go to: `Realm Settings` → `General`
    - Verify: Realm name is `henkan`
    - Go to: `Realm Settings` → `Login`
    - Enable: `User registration` (optional, for testing)
    - Enable: `Forgot password` (optional)

### 1.3 Verify Client Configuration

1. **Navigate to Client**:
    - Click `Clients` (left menu)
    - Find and click `henkan-designer`

2. **Settings Tab - CRITICAL SETTINGS**:
   ```
   Client ID: henkan-designer
   Client Protocol: openid-connect
   Access Type: confidential
   Standard Flow Enabled: ON
   Direct Access Grants Enabled: ON
   Valid Redirect URIs: 
     - http://theia.localtest.me:8080/*
     - http://localhost:8080/*
     - http://192.168.1.56:8080/*
   Web Origins: 
     - http://theia.localtest.me:8080
     - http://localhost:8080
     - http://192.168.1.56:8080
   ```

3. **Credentials Tab**:
    - Copy the `Secret` (should be: `ozX1h8tTCm6N54zFAvyUBF2c1nduIoZe`)
    - If different, update your `oauth2-proxy-config.yaml`

4. **Mappers Tab** (Optional - for debugging):
    - Add a mapper for `preferred_username` if not present:
        - Name: `username`
        - Mapper Type: `User Property`
        - Property: `username`
        - Token Claim Name: `preferred_username`
        - Claim JSON Type: `String`
        - Add to ID token: ON
        - Add to access token: ON
        - Add to userinfo: ON

### 1.4 Verify User

1. **Navigate to Users**:
    - Click `Users` (left menu)
    - Click `View all users`
    - Find user: `bartu`

2. **Check User Details**:
    - Click on `bartu`
    - Tab: `Details`
        - Email: `bartukacar405@gmail.com`
        - Email verified: ON (important!)
        - Enabled: ON

3. **Reset Password** (if needed):
    - Tab: `Credentials`
    - Click `Set Password`
    - Password: `bartu`
    - Temporary: OFF
    - Click `Set Password`

---

## Part 2: Verify Kubernetes Setup

### 2.1 Check Ingress Controller

```bash
# Check ingress controller is running
kubectl get pods -n ingress-nginx

# Check ingress controller service
kubectl get svc -n ingress-nginx

# You should see a LoadBalancer or NodePort service
# For Docker Desktop, it should be on localhost:8080
```

### 2.2 Port Forward (if needed)

If the ingress is not accessible on port 8080, forward it:

```bash
# Find the ingress controller pod
kubectl get pods -n ingress-nginx

# Port forward (run in a separate terminal)
kubectl port-forward -n ingress-nginx \
  svc/ingress-nginx-controller 8080:80

# Keep this running!
```

### 2.3 Test DNS Resolution

```bash
# theia.localtest.me should resolve to 127.0.0.1
ping -c 1 theia.localtest.me

# If it doesn't work, add to /etc/hosts (or C:\Windows\System32\drivers\etc\hosts on Windows):
# 127.0.0.1 theia.localtest.me
```

---

## Part 3: Apply ConfigMaps

### 3.1 Update OAuth2 ConfigMap

**IMPORTANT**: Your current config has the wrong Keycloak URL. Update it:

```bash
# Create the corrected oauth2-proxy-config.yaml
cat > /tmp/oauth2-proxy-config.yaml <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: oauth2-proxy-config
  namespace: default
data:
  oauth2-proxy.cfg: |
    # Provider config
    provider="keycloak-oidc"
    oidc_issuer_url="http://192.168.1.56:8080/auth/realms/henkan"
    ssl_insecure_skip_verify=true
    
    # Placeholder for redirect URL (replaced by operator)
    redirect_url="http://theia.localtest.me:8080/SESSION_UID_PLACEHOLDER/oauth2/callback"
    
    # Client config
    client_id="henkan-designer"
    client_secret="ozX1h8tTCm6N54zFAvyUBF2c1nduIoZe"
    cookie_secret="a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
    cookie_secure=false
    
    # Upstream config
    http_address="0.0.0.0:5000"
    upstreams="http://127.0.0.1:placeholder-port/"
    
    # Proxy Config
    user_id_claim="preferred_username"
    skip_auth_routes=["/health.*","/monitor/.*"]
    skip_provider_button=true
    reverse_proxy=true
    
    # Cookie and whitelist domains
    cookie_domains=[".theia.localtest.me"]
    whitelist_domains=[".theia.localtest.me:*", "theia.localtest.me:*", "192.168.1.56:*"]
    
    email_domains=["*"]
    insecure_oidc_allow_unverified_email=true
EOF

kubectl apply -f /tmp/oauth2-proxy-config.yaml
```

### 3.2 Apply OAuth2 Templates

```bash
cat > /tmp/oauth2-templates.yaml <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: oauth2-templates
  namespace: default
data:
  sign_in.html: |
    <!DOCTYPE html>
    <html>
    <head><title>Sign In</title></head>
    <body style="font-family: Arial; text-align: center; padding-top: 100px;">
      <h1>Theia Cloud - Sign In</h1>
      <form method="GET" action="{{.ProxyPrefix}}/start">
        <input type="hidden" name="rd" value="{{.Redirect}}">
        <button type="submit" style="padding: 10px 20px; font-size: 16px;">
          Sign in with Keycloak
        </button>
      </form>
    </body>
    </html>
  error.html: |
    <!DOCTYPE html>
    <html>
    <head><title>Access Denied</title></head>
    <body style="font-family: Arial; text-align: center; padding-top: 100px;">
      <h1>403 - Access Forbidden</h1>
      <p>You don't have permission to access this resource.</p>
    </body>
    </html>
EOF

kubectl apply -f /tmp/oauth2-templates.yaml
```

---

## Part 4: Create Test Resources

### 4.1 Apply CRDs (if not already done)

```bash
# Apply your CRDs
kubectl apply -f appdefinition-crd.yaml
kubectl apply -f workspace-crd.yaml
kubectl apply -f session-crd.yaml  # You should have this
```

### 4.2 Create Test AppDefinition

```bash
cat > /tmp/test-appdef.yaml <<'EOF'
apiVersion: example.suleyman.io/v1
kind: AppDefinition
metadata:
  name: sample-app
  namespace: default
spec:
  name: sample-app
  image: theiaide/theia-full:latest
  imagePullPolicy: IfNotPresent
  uid: 101
  port: 3000
  ingressname: sample-app-ingress
  minInstances: 0
  maxInstances: 10
  requestsCpu: "500m"
  requestsMemory: "512Mi"
  limitsCpu: "1000m"
  limitsMemory: "1Gi"
  mountPath: /home/project
  timeout: 1800000
EOF

kubectl apply -f /tmp/test-appdef.yaml
```

### 4.3 Create Shared Ingress (if not exists)

```bash
cat > /tmp/shared-ingress.yaml <<'EOF'
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: sample-app-ingress
  namespace: default
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /$2
    nginx.ingress.kubernetes.io/use-regex: "true"
    nginx.ingress.kubernetes.io/proxy-body-size: "1m"
    nginx.ingress.kubernetes.io/proxy-buffer-size: "128k"
  ownerReferences:
    - apiVersion: example.suleyman.io/v1
      kind: AppDefinition
      name: sample-app
      uid: REPLACE_WITH_APPDEF_UID  # Get from: kubectl get appdef sample-app -o yaml | grep uid
spec:
  ingressClassName: nginx
  rules:
    - host: theia.localtest.me
EOF

# Get the AppDefinition UID
APPDEF_UID=$(kubectl get appdef sample-app -o jsonpath='{.metadata.uid}')

# Replace UID in the file
sed -i.bak "s/REPLACE_WITH_APPDEF_UID/$APPDEF_UID/" /tmp/shared-ingress.yaml

kubectl apply -f /tmp/shared-ingress.yaml
```

### 4.4 Create Test Workspace

```bash
cat > /tmp/test-workspace.yaml <<'EOF'
apiVersion: example.suleyman.io/v1
kind: Workspace
metadata:
  name: bartu-workspace
  namespace: default
spec:
  name: bartu-workspace
  label: "Bartu's Test Workspace"
  user: bartu
  appDefinition: sample-app
EOF

kubectl apply -f /tmp/test-workspace.yaml

# Wait for workspace to be ready
kubectl get workspace bartu-workspace -w
# Press Ctrl+C when operatorStatus is HANDLED
```

### 4.5 Create Test Session

```bash
cat > /tmp/test-session.yaml <<'EOF'
apiVersion: example.suleyman.io/v1
kind: Session
metadata:
  name: bartu-session-1
  namespace: default
spec:
  name: bartu-session-1
  user: bartu
  workspace: bartu-workspace
  appDefinition: sample-app
  envVars:
    CUSTOM_VAR: "test-value"
EOF

kubectl apply -f /tmp/test-session.yaml

# Watch the session being created
kubectl get session bartu-session-1 -w
# Press Ctrl+C when operatorStatus is HANDLED
```

---

## Part 5: Test the Authentication Flow

### 5.1 Get Session Details

```bash
# Get the session UID
SESSION_UID=$(kubectl get session bartu-session-1 -o jsonpath='{.metadata.uid}')
echo "Session UID: $SESSION_UID"

# Get the session URL
SESSION_URL=$(kubectl get session bartu-session-1 -o jsonpath='{.status.url}')
echo "Session URL: http://$SESSION_URL"

# Or construct it manually:
echo "Full URL: http://theia.localtest.me:8080/$SESSION_UID/"
```

### 5.2 Check Resources Created

```bash
# Check deployment
kubectl get deployment -l theia-cloud.io/user=bartu

# Check service  
kubectl get service -l theia-cloud.io/user=bartu

# Check ingress paths
kubectl get ingress sample-app-ingress -o yaml | grep -A 5 "rules:"

# Check oauth2-proxy config was created
kubectl get configmap | grep session-proxy

# Check the rendered config
kubectl get configmap $(kubectl get cm | grep session-proxy | awk '{print $1}') -o yaml
```

### 5.3 Test Authentication Flow

**Step 1: Open Browser**

```bash
# Get the session UID if you haven't already
SESSION_UID=$(kubectl get session bartu-session-1 -o jsonpath='{.metadata.uid}')

# Open in browser:
open http://theia.localtest.me:8080/$SESSION_UID/

# Or just print it and copy to browser:
echo "http://theia.localtest.me:8080/$SESSION_UID/"
```

**Step 2: Expected Flow**

1. **First Request**: You should be redirected to Keycloak login page
    - URL will be: `http://192.168.1.56:8080/auth/realms/henkan/protocol/openid-connect/auth?...`
    - Page shows: Keycloak login form

2. **Login**:
    - Username: `bartu`
    - Password: `bartu`
    - Click `Sign In`

3. **Redirect Back**:
    - After successful login, you'll be redirected to: `http://theia.localtest.me:8080/{session-uid}/oauth2/callback?...`
    - OAuth2-proxy will validate the token

4. **Final Redirect**:
    - You should be redirected to: `http://theia.localtest.me:8080/{session-uid}/`
    - You should see the Theia IDE loading (might take 30-60 seconds for first start)

---

## Part 6: Debugging

### 6.1 Check OAuth2-Proxy Logs

```bash
# Get the pod name
POD_NAME=$(kubectl get pods -l theia-cloud.io/user=bartu -o jsonpath='{.items[0].metadata.name}')

# Check oauth2-proxy container logs
kubectl logs $POD_NAME -c oauth2-proxy

# Follow logs in real-time
kubectl logs $POD_NAME -c oauth2-proxy -f
```

**What to look for:**
- `GET /` → Should show incoming requests
- `GET /oauth2/callback` → OAuth callback
- `authenticated via session cookie` → Successful auth
- `Error redeeming code` → Auth failure (check client secret)

### 6.2 Check Theia Container Logs

```bash
# Check main application container
kubectl logs $POD_NAME -c theia

# Follow logs
kubectl logs $POD_NAME -c theia -f
```

### 6.3 Common Issues and Solutions

#### Issue 1: "Redirect URI mismatch"

**Symptom**: Keycloak shows error about redirect URI

**Solution**:
```bash
# In Keycloak Admin Console:
# Clients → henkan-designer → Settings → Valid Redirect URIs
# Add:
http://theia.localtest.me:8080/*
http://localhost:8080/*

# Then click Save
```

#### Issue 2: "Invalid client secret"

**Symptom**: OAuth2-proxy logs show "error redeeming code"

**Solution**:
```bash
# Get the correct secret from Keycloak:
# Clients → henkan-designer → Credentials tab → Secret

# Update your ConfigMap:
kubectl edit configmap oauth2-proxy-config

# Update the client_secret value
# Then delete the pod to recreate with new config:
kubectl delete pod $POD_NAME
```

#### Issue 3: "404 Not Found" or "503 Service Unavailable"

**Symptom**: Browser shows nginx error

**Solution**:
```bash
# Check if ingress has the path:
kubectl get ingress sample-app-ingress -o yaml

# Check if service exists:
kubectl get svc -l theia-cloud.io/user=bartu

# Check if pod is running:
kubectl get pods -l theia-cloud.io/user=bartu

# Check pod status:
kubectl describe pod $POD_NAME
```

#### Issue 4: "Cookie domain mismatch"

**Symptom**: Redirect loop, keeps going back to Keycloak

**Solution**:
```bash
# Check oauth2-proxy config:
kubectl get cm $(kubectl get cm | grep session-proxy | awk '{print $1}') -o yaml

# Ensure cookie_domains includes:
cookie_domains=[".theia.localtest.me"]

# If wrong, the operator should regenerate it
# Delete session and recreate:
kubectl delete session bartu-session-1
kubectl apply -f /tmp/test-session.yaml
```

### 6.4 Test Keycloak Connectivity from Pod

```bash
# Exec into the oauth2-proxy container
kubectl exec -it $POD_NAME -c oauth2-proxy -- sh

# Test Keycloak reachability
wget -O- http://192.168.1.56:8080/auth/realms/henkan/.well-known/openid-configuration

# You should see JSON with Keycloak endpoints
```

### 6.5 Verify Ingress Routing

```bash
# Test from your machine
curl -v http://theia.localtest.me:8080/$SESSION_UID/

# You should get a redirect (302) to Keycloak
```

---

## Part 7: Verify OAuth2 Flow Step-by-Step

### Manual Test with curl

```bash
SESSION_UID=$(kubectl get session bartu-session-1 -o jsonpath='{.metadata.uid}')

# Step 1: Initial request (should redirect to Keycloak)
curl -i http://theia.localtest.me:8080/$SESSION_UID/

# Look for:
# HTTP/1.1 302 Found
# Location: http://192.168.1.56:8080/auth/realms/henkan/protocol/openid-connect/auth?...

# Step 2: Get the login page
curl -L http://192.168.1.56:8080/auth/realms/henkan/protocol/openid-connect/auth?client_id=henkan-designer&response_type=code

# Should return HTML login form
```

---

## Part 8: Success Criteria

✅ **Authentication Flow Works When:**

1. Opening session URL redirects to Keycloak
2. Login with bartu/bartu succeeds
3. Redirect back to session URL happens automatically
4. Theia IDE loads (may take time on first start)
5. Cookie is set (check browser dev tools → Application → Cookies)

✅ **OAuth2-Proxy Logs Show:**
```
[oauth2-proxy] provider.keycloak.com: created provider with OIDC endpoint http://192.168.1.56:8080/auth/realms/henkan
[oauth2-proxy] successfully verified id_token
[oauth2-proxy] authenticated via session cookie
```

✅ **Browser Shows:**
- Theia IDE interface
- URL stays at `http://theia.localtest.me:8080/{session-uid}/`
- No redirect loops

---

## Next Steps

After successful authentication test:

1. **Test multiple sessions**: Create another session for the same user
2. **Test session limits**: Verify SESSIONS_PER_USER works
3. **Test logout**: Try the logout flow
4. **Test session timeout**: Verify timeout configuration
5. **Test different users**: Create another Keycloak user and test

---

## Quick Reference Commands

```bash
# Get session UID and URL
kubectl get session bartu-session-1 -o jsonpath='{.metadata.uid}'
kubectl get session bartu-session-1 -o jsonpath='{.status.url}'

# Watch session creation
kubectl get session bartu-session-1 -w

# Check logs
POD=$(kubectl get pods -l theia-cloud.io/user=bartu -o jsonpath='{.items[0].metadata.name}')
kubectl logs $POD -c oauth2-proxy -f

# Delete and recreate session
kubectl delete session bartu-session-1
kubectl apply -f /tmp/test-session.yaml

# Check all resources
kubectl get appdef,workspace,session,pod,svc,ingress -l theia-cloud.io/user=bartu
```