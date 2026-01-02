// File: SessionReconciler.kt
package com.example.hello

import controllerOwnerRef
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.javaoperatorsdk.operator.api.reconciler.Cleaner
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import org.slf4j.LoggerFactory
import ownerRef
import java.io.ByteArrayInputStream
import java.time.Duration

@ControllerConfiguration(
    name = "session-controller",
    finalizerName = SessionReconciler.FINALIZER_NAME
)
class SessionReconciler(
    private val client: KubernetesClient,
    private val config: OperatorConfig
) : Reconciler<Session>, Cleaner<Session> {

    companion object {
        const val FINALIZER_NAME = "sessions.example.suleyman.io/finalizer"
        private const val SERVICE_PORT_NAME = "http" // must match Service port name
        // One shared Ingress per AppDefinition, like Henkan
        // Prefer the explicit spec.ingressname, fall back to a derived name.
        fun ingressNameForAppDef(appDef: AppDefinition): String =
            appDef.spec?.ingressname
                ?: "theia-${appDef.metadata?.name ?: "unknown-app"}-ingress"
    }

    private val log = LoggerFactory.getLogger(SessionReconciler::class.java)


    private val sessionsPerUser: Int? = config.sessionsPerUser

    private val keycloakUrl: String? = config.keycloakUrl
    private val keycloakRealm: String? = config.keycloakRealm
    private val keycloakClientId: String? = config.keycloakClientId

    private val ingressHost: String = (config.instancesHost ?: "theia.localtest.me").trim()
    private val ingressScheme: String = config.ingressScheme.trim()


    override fun reconcile(resource: Session, context: Context<Session>): UpdateControl<Session> {
        val ns = resource.metadata?.namespace ?: "default"
        val k8sName = resource.metadata?.name ?: "<no-name>"

        val meta = resource.metadata
        if (meta == null) {
            log.warn("Session {}/{} has no metadata, skipping", ns, k8sName)
            return UpdateControl.noUpdate()
        }

        val spec = resource.spec
        log.info("Reconciling Session {}/{} spec={}", ns, k8sName, spec)

        // --- 1) Basic validation of spec
        if (spec == null) {
            return failStatus(resource, "spec is null on Session {}/$k8sName".format(ns))
        }

        val sessionName = spec.name
        val workspaceName = spec.workspace
        val appDefName = spec.appDefinition
        val user = spec.user
        val envVarsFromConfigMaps = spec.envVarsFromConfigMaps ?: emptyList()
        val envVarsFromSecrets = spec.envVarsFromSecrets ?: emptyList()
        val sessionSecret = spec.sessionSecret

        if (sessionName.isNullOrBlank() ||
            workspaceName.isNullOrBlank() ||
            appDefName.isNullOrBlank() ||
            user.isNullOrBlank()
        ) {
            return failStatus(
                resource,
                "Missing required spec fields: name='$sessionName', workspace='$workspaceName', appDefinition='$appDefName', user='$user'"
            )
        }

        // After this point we treat them as non-null
        val nonNullSessionName = sessionName
        val nonNullWorkspaceName = workspaceName
        val nonNullAppDefName = appDefName

        log.info(
            "Reconciling logical Session name='{}' workspace='{}' appDefinition='{}' user='{}'",
            nonNullSessionName, nonNullWorkspaceName, nonNullAppDefName, user
        )

        // --- 1.5) Only one *active* Session per Workspace
        val otherActiveSessions = client.resources(Session::class.java)
            .inNamespace(ns)
            .list()
            .items
            .filter { it.metadata?.uid != resource.metadata?.uid }
            .filter { it.spec?.workspace == nonNullWorkspaceName }
            .filter { it.status?.operatorStatus == "HANDLED" }
            .filter { it.metadata?.deletionTimestamp == null }

        if (otherActiveSessions.isNotEmpty()) {
            val existing = otherActiveSessions.first()
            return failStatus(
                resource,
                "Workspace '$nonNullWorkspaceName' already has active session '${existing.spec?.name ?: existing.metadata?.name}'"
            )
        }

        // --- 1.6) Optional: max sessions per user (SESSIONS_PER_USER)
        if (sessionsPerUser != null && sessionsPerUser > 0) {
            val allUserSessions = client.resources(Session::class.java)
                .inNamespace(ns)
                .list()
                .items
                .filter { it.spec?.user == user }
                .filter { it.metadata?.uid != resource.metadata?.uid }
                .filter { it.status?.operatorStatus == "HANDLED" }
                .filter { it.metadata?.deletionTimestamp == null }



            val activeCount = allUserSessions.size

            if (activeCount >= sessionsPerUser) {
                return failStatus(
                    resource,
                    "User '$user' already has $activeCount active sessions, which meets or exceeds limit $sessionsPerUser"
                )
            }
        }

        // --- 2) Load AppDefinition
        val appDef = client.resources(AppDefinition::class.java)
            .inNamespace(ns)
            .withName(nonNullAppDefName)
            .get()
            ?: return failStatus(resource, "AppDefinition '$nonNullAppDefName' not found in '$ns'")

        val appSpec = appDef.spec
            ?: return failStatus(resource, "AppDefinition '$nonNullAppDefName' has no spec")

        val image = appSpec.image
            ?: return failStatus(resource, "AppDefinition '$nonNullAppDefName' missing image")
        val port = appSpec.port
            ?: return failStatus(resource, "AppDefinition '$nonNullAppDefName' missing port")

        val appPort = port          // from AppDefinition, usually 3000
        val proxyPort = 5000        // constant


        // NEW: monitor config
        val monitorPort = appSpec.monitor?.port
        val hasActivityTracker = appSpec.monitor?.activityTracker != null
        val activityTracker = appSpec.monitor?.activityTracker
        val activityTimeout = activityTracker?.timeoutAfter
        val activityNotifyAfter = activityTracker?.notifyAfter

        // Optional fields from AppDefinition
        val imagePullPolicy = appSpec.imagePullPolicy ?: "IfNotPresent"
        val pullSecret = appSpec.pullSecret
        val downlinkLimit = appSpec.downlinkLimit
        val uplinkLimit = appSpec.uplinkLimit

        // --- 2.5) Workspace existence
        val workspace = client.resources(Workspace::class.java)
            .inNamespace(ns)
            .withName(nonNullWorkspaceName)
            .get()
            ?: return failStatus(resource, "Workspace '$nonNullWorkspaceName' not found in '$ns'")

        // --- 2.5.1) Workspace must have a storage PVC name, wait until it is created
        val workspacePvcName = workspace.spec?.storage
        if (workspacePvcName.isNullOrBlank()) {
            val st = ensureStatus(resource)
            st.operatorStatus = "HANDLING"
            st.operatorMessage = "Waiting for Workspace '$nonNullWorkspaceName' to get spec.storage"
            st.error = null
            return UpdateControl.patchStatus(resource).rescheduleAfter(Duration.ofSeconds(2))
        }

        val pvc = client.persistentVolumeClaims().inNamespace(ns).withName(workspacePvcName).get()
        if (pvc == null || pvc.metadata?.deletionTimestamp != null) {
            val st = ensureStatus(resource)
            st.operatorStatus = "HANDLING"
            st.operatorMessage = "Waiting for PVC '$workspacePvcName' to exist and not be terminating"
            st.error = null
            return UpdateControl.patchStatus(resource).rescheduleAfter(Duration.ofSeconds(2))
        }



        var metadataChanged = false

        // 2.6 ownerRef (NO client.patch here)
        val existingRefs = (meta.ownerReferences ?: emptyList()).toMutableList()

        val newRefs = existingRefs
            .filterNot { it.controller == true }  // drop any previous controller owner
            .toMutableList()

        newRefs.add(controllerOwnerRef(workspace))
        meta.ownerReferences = newRefs
        metadataChanged = true



        // --- 2.7) Session and Workspace must agree on appDefinition
        val wsAppDefName = workspace.spec?.appDefinition
        if (!wsAppDefName.isNullOrBlank() && wsAppDefName != nonNullAppDefName) {
            return failStatus(
                resource,
                "Workspace '$nonNullWorkspaceName' uses appDefinition '$wsAppDefName' but session uses '$nonNullAppDefName'"
            )
        }

        // 2.8 labels (NO client.patch here)
        val labels = (meta.labels ?: emptyMap()).toMutableMap()
        fun putIfMissing(k: String, v: String?) {
            if (!v.isNullOrBlank() && !labels.containsKey(k)) {
                labels[k] = v
                metadataChanged = true
            }
        }

        putIfMissing("app.henkan.io/workspaceName", toHenkanLabelValue(nonNullWorkspaceName))
        putIfMissing("app.henkan.io/workspaceUser", toHenkanLabelValue(user))
        putIfMissing("app.henkan.io/henkanProjectName", toHenkanLabelValue(workspace.spec?.label))
        meta.labels = labels

        // --- 3) Env: system THEIACLOUD_* + user envVars
        val sessionEnvMap = spec.envVars ?: emptyMap()

        val appId = config.appId ?: appSpec.name ?: appDef.metadata?.name ?: nonNullAppDefName

        val sessionUid = resource.metadata?.uid
            ?: return failStatus(resource, "Session UID is missing")

        val sessionBaseName = sessionResourceBaseName(user, nonNullAppDefName, sessionUid)

        val serviceName = sessionBaseName
        val serviceUrl = "http://$serviceName:$port"



        // Henkan-like URLs:
        val sessionUrlForEnv = "${ingressScheme}://$ingressHost/$sessionUid/"
        val sessionUrlForStatus = "$ingressHost/$sessionUid/"

        val mergedEnv = mutableListOf<EnvVar>()

        mergedEnv += EnvVar("THEIACLOUD_APP_ID", appId, null)
        mergedEnv += EnvVar("THEIACLOUD_SERVICE_URL", serviceUrl, null)
        mergedEnv += EnvVar("THEIACLOUD_SESSION_UID", sessionUid, null)
        mergedEnv += EnvVar("THEIACLOUD_SESSION_NAME", nonNullSessionName, null)
        mergedEnv += EnvVar("THEIACLOUD_SESSION_USER", user, null)
        mergedEnv += EnvVar("THEIACLOUD_SESSION_URL", sessionUrlForEnv, null)

        if (!sessionSecret.isNullOrBlank()) {
            mergedEnv += EnvVar("THEIACLOUD_SESSION_SECRET", sessionSecret, null)
        }

        keycloakUrl?.let { mergedEnv += EnvVar("THEIACLOUD_KEYCLOAK_URL", it, null) }
        keycloakRealm?.let { mergedEnv += EnvVar("THEIACLOUD_KEYCLOAK_REALM", it, null) }
        keycloakClientId?.let { mergedEnv += EnvVar("THEIACLOUD_KEYCLOAK_CLIENT_ID", it, null) }

        if (monitorPort != null) {
            mergedEnv += EnvVar("THEIACLOUD_MONITOR_PORT", monitorPort.toString(), null)
        }
        if (hasActivityTracker) {
            mergedEnv += EnvVar("THEIACLOUD_MONITOR_ENABLE_ACTIVITY_TRACKER", "true", null)
        }
        activityTimeout?.let {
            mergedEnv += EnvVar("THEIACLOUD_MONITOR_TIMEOUT_AFTER", it.toString(), null)
        }
        activityNotifyAfter?.let {
            mergedEnv += EnvVar("THEIACLOUD_MONITOR_NOTIFY_AFTER", it.toString(), null)
        }

        // User env vars, without overriding THEIACLOUD_*
        sessionEnvMap.forEach { (name, value) ->
            if (!name.startsWith("THEIACLOUD_")) {
                mergedEnv += EnvVar(name, value.toString(), null)
            } else {
                log.warn(
                    "Ignoring user env var {} on Session {}/{} because it conflicts with system THEIACLOUD_* variables",
                    name, ns, nonNullSessionName
                )
            }
        }

        log.info(
            "Merged env for Session {}/{} -> {}",
            ns,
            nonNullSessionName,
            mergedEnv.joinToString { "${it.name}=${it.value}" }
        )

        // --- 4) Resources from AppDefinition
        val requestsCpu = appSpec.requestsCpu ?: "250m"
        val requestsMemory = appSpec.requestsMemory ?: "512Mi"
        val limitsCpu = appSpec.limitsCpu ?: requestsCpu
        val limitsMemory = appSpec.limitsMemory ?: requestsMemory
        val mountPath = appSpec.mountPath ?: "/home/project"

        // --- 4.5) Security context UIDs from AppDefinition (or default to 101)
        val defaultUid = 101
        val runAsUid = appSpec.uid ?: defaultUid
        val fsGroupUid = appSpec.uid ?: defaultUid


        val appLabel = toHenkanLabelValue("${nonNullSessionName}-${sessionUid}") ?: "${nonNullSessionName}-${sessionUid}"

        // --- 4.9) Check for ingress existence
        val ingressName = ingressNameForAppDef(appDef)
        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val existingIngress = ingressClient.withName(ingressName).get()
        if (existingIngress == null) {
            return failStatus(resource, "Shared Ingress '$ingressName' is missing. Create it first.")
        }

        val sessionProxyCm = ensureSessionProxyConfigMap(
            namespace = ns,
            user = user,
            appDefName = nonNullAppDefName,
            sessionName = nonNullSessionName,
            sessionUid = sessionUid,
            port = appPort,
            owner = resource
        )

        val sessionEmailCm = ensureSessionEmailConfigMap(
            namespace = ns,
            user = user,
            appDefName = nonNullAppDefName,
            sessionName = nonNullSessionName,
            sessionUid = sessionUid,
            owner = resource
        )


        // --- 5) Ensure Deployment + Service + SHARED Ingress
        ensureTheiaDeployment(
            namespace = ns,
            deploymentName = sessionBaseName,
            sessionName = nonNullSessionName,
            workspaceName = nonNullWorkspaceName,
            image = image,
            imagePullPolicy = imagePullPolicy,
            pullSecret = pullSecret,
            requestsCpu = requestsCpu,
            requestsMemory = requestsMemory,
            limitsCpu = limitsCpu,
            limitsMemory = limitsMemory,
            env = mergedEnv,
            envVarsFromConfigMaps = envVarsFromConfigMaps,
            envVarsFromSecrets = envVarsFromSecrets,
            oauth2ProxyConfigMapName = sessionProxyCm,
            oauth2EmailsConfigMapName = sessionEmailCm,
            port = port,
            monitorPort = monitorPort,
            mountPath = mountPath,
            fsGroupUid = fsGroupUid,
            runAsUid = runAsUid,
            downlinkLimit = downlinkLimit,
            uplinkLimit = uplinkLimit,
            appDefinitionName = nonNullAppDefName,
            user = user,
            sessionUid = sessionUid,
            owner = resource,
            pvcName = workspacePvcName,
            appLabel = appLabel,
        )

        ensureTheiaService(
            namespace = ns,
            serviceName = serviceName,
            sessionName = nonNullSessionName,
            port = proxyPort,
            owner = resource,
            appLabel = appLabel,
            appDefinitionName = nonNullAppDefName,
            user = user,
        )

        ensureSharedIngressForSession(
            session = resource,
            serviceName = serviceName,
            ingressName = ingressName,
            appDef = appDef,
            port = proxyPort
        )


        // --- 6) Status on success
        val status = ensureStatus(resource)
        status.operatorStatus = "HANDLED"
        status.operatorMessage = "Session is running"
        status.url = sessionUrlForStatus
        status.lastActivity = System.currentTimeMillis()

        return if (metadataChanged) {
            UpdateControl.patchResourceAndStatus(resource)   // ✅ single write for CR + status
        } else {
            UpdateControl.patchStatus(resource)              // ✅ only status
        }
    }

    override fun cleanup(resource: Session, context: Context<Session>): DeleteControl {
        val ns = resource.metadata?.namespace ?: "default"
        val name = resource.metadata?.name ?: "<no-name>"

        val appDefName = resource.spec?.appDefinition
        if (appDefName.isNullOrBlank()) {
            log.warn(
                "Session {}/{} has no spec.appDefinition, cannot determine shared Ingress name, skipping ingress cleanup",
                ns, name
            )
            return DeleteControl.defaultDelete()
        }

        // Load AppDefinition so we can determine the ingress name
        val appDef = client.resources(AppDefinition::class.java)
            .inNamespace(ns)
            .withName(appDefName)
            .get()

        if (appDef == null) {
            log.warn(
                "AppDefinition '{}' not found in namespace {}, cannot determine shared Ingress name, skipping ingress cleanup",
                appDefName, ns
            )
            return DeleteControl.defaultDelete()
        }

        val ingressName = ingressNameForAppDef(appDef)
        val host = sessionHost(resource)
        val path = sessionPath(resource)

        log.info(
            "Running cleanup for Session {}/{}: removing path {} on host {} from shared Ingress '{}'",
            ns, name, path, host, ingressName
        )

        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val ingress = ingressClient.withName(ingressName).get()

        if (ingress == null) {
            log.info(
                "Shared Ingress '{}' not found in namespace {}, nothing to cleanup",
                ingressName, ns
            )
            return DeleteControl.defaultDelete()
        }

        val rules = ingress.spec?.rules?.toMutableList() ?: mutableListOf()
        var changed = false

        // Build a new rules list, but never delete the Ingress itself
        val newRules = rules.map { rule ->
            if (rule.host != host) {
                rule
            } else {
                val http = rule.http
                val paths = http?.paths ?: emptyList()

                val filteredPaths = paths.filter { it.path != path }

                if (filteredPaths.size == paths.size) {
                    // nothing removed
                    rule
                } else {
                    changed = true
                    if (filteredPaths.isEmpty()) {
                        // keep host, drop http -> host-only rule, no active sessions
                        IngressRuleBuilder(rule)
                            .withHttp(null)
                            .build()
                    } else {
                        // still some paths left for this host
                        IngressRuleBuilder(rule)
                            .editOrNewHttp()
                            .withPaths(filteredPaths)
                            .endHttp()
                            .build()
                    }
                }
            }
        }.toMutableList()



        if (!changed) {
            log.info(
                "No matching path {} on host {} in Ingress '{}', nothing to change",
                path, host, ingressName
            )
            return DeleteControl.defaultDelete()
        }

        val normalized = normalizeIngressRules(newRules)
        val updatedIngress = IngressBuilder(ingress)
            .editOrNewSpec()
            .withRules(normalized)
            .endSpec()
            .build()


        ingressClient.resource(updatedIngress).createOrReplace()

        return DeleteControl.defaultDelete()
    }



    // === Helpers for URL/paths =============================================================

    private fun sessionHost(@Suppress("UNUSED_PARAMETER") session: Session): String =
        ingressHost

    private fun sessionPath(session: Session): String =
        "/${session.metadata?.uid}(/|$)(.*)"

    // === Status & error handling ===========================================================

    private fun failStatus(resource: Session, msg: String): UpdateControl<Session> {
        val status = ensureStatus(resource)
        status.operatorStatus = "ERROR"
        status.operatorMessage = msg
        status.error = msg
        status.url = null
        return UpdateControl.patchStatus(resource)
    }

    private fun ensureStatus(resource: Session): SessionStatus {
        if (resource.status == null) {
            resource.status = SessionStatus()
        }
        return resource.status!!
    }

    // === Deployment / Service creation via templates =======================================

    private fun ensureTheiaDeployment(
        namespace: String,
        deploymentName: String,
        sessionName: String,
        workspaceName: String,
        image: String,
        imagePullPolicy: String,
        pullSecret: String?,
        requestsCpu: String,
        requestsMemory: String,
        limitsCpu: String,
        limitsMemory: String,
        env: List<EnvVar>,
        envVarsFromConfigMaps: List<String>,
        envVarsFromSecrets: List<String>,
        oauth2ProxyConfigMapName: String,
        oauth2EmailsConfigMapName: String,
        port: Int,
        monitorPort: Int?,
        mountPath: String,
        fsGroupUid: Int,
        runAsUid: Int,
        downlinkLimit: Int?,
        uplinkLimit: Int?,
        appDefinitionName: String,
        user: String,
        sessionUid: String,
        owner: Session,
        pvcName: String,
        appLabel: String,
    ) {

        log.info(
            "Ensuring Deployment {} in ns {} using PVC {}",
            deploymentName, namespace, pvcName
        )

        val yaml = TemplateRenderer.render(
            "templates/theia-deployment.yaml.vm",
            mapOf(
                "namespace" to namespace,
                "deploymentName" to deploymentName,
                "sessionName" to sessionName,
                "workspaceName" to workspaceName,
                "pvcName" to pvcName,
                "image" to image,
                "imagePullPolicy" to imagePullPolicy,
                "pullSecret" to pullSecret,
                "requestsCpu" to requestsCpu,
                "requestsMemory" to requestsMemory,
                "limitsCpu" to limitsCpu,
                "limitsMemory" to limitsMemory,
                "envs" to env,
                "envVarsFromConfigMaps" to envVarsFromConfigMaps,
                "envVarsFromSecrets" to envVarsFromSecrets,
                "port" to port,
                "monitorPort" to monitorPort,
                "mountPath" to mountPath,
                "fsGroupUid" to fsGroupUid,
                "runAsUid" to runAsUid,
                "oauth2ProxyImage" to config.oAuth2ProxyImage,
                "oauth2ProxyConfigMapName" to oauth2ProxyConfigMapName,
                "oauth2TemplatesConfigMapName" to "oauth2-templates",
                "oauth2EmailsConfigMapName" to oauth2EmailsConfigMapName,
                "downlinkLimit" to downlinkLimit,
                "uplinkLimit" to uplinkLimit,
                "appDefinitionName" to appDefinitionName,
                "user" to user,
                "sessionUid" to sessionUid,
                "appLabel" to appLabel
            )
        )

        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            r.metadata.ownerReferences = listOf(controllerOwnerRef(owner))
            client.resource(r).inNamespace(namespace).createOrReplace()
        }
    }

    private fun ensureSessionProxyConfigMap(
        namespace: String,
        user: String,
        appDefName: String,
        sessionName: String,
        sessionUid: String,
        port: Int,
        owner: Session
    ): String {
        val baseName = "oauth2-proxy-config"
        val base = client.configMaps().inNamespace(namespace).withName(baseName).get()
            ?: throw IllegalStateException("Missing ConfigMap '$baseName' in namespace '$namespace'")

        val baseCfg = base.data?.get("oauth2-proxy.cfg")
            ?: throw IllegalStateException("ConfigMap '$baseName' missing key 'oauth2-proxy.cfg'")

        // 1. Calculate the dynamic Issuer URL
        // e.g. "http://192.168.19.251:8080/auth/" + "realms/" + "henkan"
        val issuerUrl = "${config.keycloakUrl}realms/${config.keycloakRealm}"

        val host = config.instancesHost ?: "theia.localtest.me"
        val scheme = config.ingressScheme.ifBlank { "http" }

        // For local dev with port-forward, default to 8080
        val ingressPort = System.getenv("INGRESS_PORT") ?: "8080"

        val upstream = "http://127.0.0.1:$port/"

        // Replace ALL placeholders
        val rendered = baseCfg
            .replace("ISSUER_URL_PLACEHOLDER", issuerUrl)
            .replace("SESSION_UID_PLACEHOLDER", sessionUid)
            .replace("PORT_PLACEHOLDER", ingressPort)
            .replace("http://127.0.0.1:placeholder-port/", upstream)
            .replace("placeholder-port", port.toString())
            .replace("placeholder-host", host)

        log.info("Rendered oauth2-proxy.cfg for session {}:\n{}", sessionUid, rendered)

        val name = sessionProxyCmName(user, appDefName, sessionUid)

        val cm = ConfigMapBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(namespace)
            .addToLabels("app.kubernetes.io/component", "session")
            .addToLabels("theia-cloud.io/session-uid", sessionUid)
            .addToLabels("theia-cloud.io/template-purpose", "proxy")
            .withOwnerReferences(controllerOwnerRef(owner))
            .endMetadata()
            .addToData("oauth2-proxy.cfg", rendered)
            .build()

        client.configMaps().inNamespace(namespace).resource(cm).createOrReplace()
        return name
    }

    private fun ensureSessionEmailConfigMap(
        namespace: String,
        user: String,
        appDefName: String,
        sessionName: String,
        sessionUid: String,
        owner: Session
    ): String {
        val name = sessionEmailCmName(user, appDefName, sessionUid)

        // Henkan-like: store username (e.g., "bartu") not email
        val cm = ConfigMapBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(namespace)
            .addToLabels("app.kubernetes.io/component", "session")
            .addToLabels("app.kubernetes.io/part-of", "theia-cloud")
            .addToLabels("theia-cloud.io/app-definition", appDefName)
            .addToLabels("theia-cloud.io/session", sessionName)
            .addToLabels("theia-cloud.io/user", user)
            .addToLabels("theia-cloud.io/template-purpose", "emails")
            .withOwnerReferences(controllerOwnerRef(owner))
            .endMetadata()
            .addToData("authenticated-emails-list", user)
            .build()

        client.configMaps().inNamespace(namespace).resource(cm).createOrReplace()
        return name
    }


    private fun shortUid(sessionUid: String): String =
        sessionUid.replace("-", "").takeLast(12)

    private fun safeNamePart(s: String, max: Int): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(max)

    private fun sessionProxyCmName(user: String, appDefName: String, sessionUid: String): String =
        "session-proxy-${safeNamePart(user, 20)}-${safeNamePart(appDefName, 10)}-${shortUid(sessionUid)}"

    private fun sessionEmailCmName(user: String, appDefName: String, sessionUid: String): String =
        "session-email-${safeNamePart(user, 20)}-${safeNamePart(appDefName, 10)}-${shortUid(sessionUid)}"


    private fun ensureTheiaService(
        namespace: String,
        serviceName: String,
        sessionName: String,
        port: Int,
        owner: Session,
        appLabel: String = "theia",
        appDefinitionName: String,
        user: String,
    ) {
        val yaml = TemplateRenderer.render(
            "templates/theia-service.yaml.vm",
            mapOf(
                "namespace" to namespace,
                "serviceName" to serviceName,
                "sessionName" to sessionName,
                "serviceType" to "ClusterIP",
                "servicePort" to port,
                "targetPort" to port,
                "appLabel" to appLabel,
                "appDefinitionName" to appDefinitionName,
                "user" to user,
            )
        )

        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            r.metadata.ownerReferences = listOf(controllerOwnerRef(owner))
            client.resource(r).inNamespace(namespace).createOrReplace()
        }

        log.info("Rendered Service YAML:\n{}", yaml)
    }

    // === Shared Ingress helper =============================================================

    private fun ensureSharedIngressForSession(
        session: Session,
        serviceName: String,
        ingressName: String,
        appDef: AppDefinition,
        port: Int
    ) {
        val ns = session.metadata?.namespace ?: "default"
        val host = sessionHost(session)
        val path = sessionPath(session)

        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val existing = ingressClient.withName(ingressName).get()
            ?: throw IllegalStateException("Shared Ingress '$ingressName' is missing") // should not happen because you check earlier

        val ingress = IngressBuilder(existing).build()
        val rules = ingress.spec?.rules?.toMutableList() ?: mutableListOf()

        // Prefer a rule with http; do NOT hijack a host-only rule
        var ruleIndex = rules.indexOfFirst { it.host == host && it.http != null }

        if (ruleIndex == -1) {
            // No http rule yet -> add a new one, keep existing host-only rule untouched
            val newRule = IngressRuleBuilder()
                .withHost(host)
                .withNewHttp()
                .addNewPath()
                .withPath(path)
                .withPathType("ImplementationSpecific")
                .withNewBackend()
                .withNewService()
                .withName(serviceName)
                .withNewPort().withNumber(port).endPort()
                .endService()
                .endBackend()
                .endPath()
                .endHttp()
                .build()
            rules.add(newRule)
        } else {
            val rule = rules[ruleIndex]
            val paths = rule.http?.paths?.toMutableList() ?: mutableListOf()

            val existingPathIndex = paths.indexOfFirst { it.path == path }
            if (existingPathIndex == -1) {
                val newPath = HTTPIngressPathBuilder()
                    .withPath(path)
                    .withPathType("ImplementationSpecific")
                    .withNewBackend()
                    .withNewService()
                    .withName(serviceName)
                    .withNewPort().withNumber(port).endPort()
                    .endService()
                    .endBackend()
                    .build()
                paths.add(newPath)
            } else {
                val existingPath = paths[existingPathIndex]
                existingPath.backend?.service?.name = serviceName
                existingPath.backend?.service?.port?.number = port
                existingPath.backend?.service?.port?.name = null
            }

            rules[ruleIndex] = IngressRuleBuilder(rule)
                .editOrNewHttp()
                .withPaths(paths)
                .endHttp()
                .build()
        }

        val normalized = normalizeIngressRules(rules)
        ingress.spec?.rules = normalized
        ingressClient.resource(ingress).createOrReplace()
    }

    private fun normalizeIngressRules(rules: List<io.fabric8.kubernetes.api.model.networking.v1.IngressRule>):
            MutableList<io.fabric8.kubernetes.api.model.networking.v1.IngressRule> {

        val out = mutableListOf<io.fabric8.kubernetes.api.model.networking.v1.IngressRule>()

        for (r in rules) {
            // Only keep rules that have actual paths
            if (r.http != null) {
                out.add(r)
            }
        }
        return out
    }

    private fun toK8sName(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(253)

    private fun sessionResourceBaseName(user: String, appDefName: String, sessionUid: String): String {
        val uidSuffix = sessionUid.substringAfterLast('-')
        return toK8sName("session-$user-$appDefName-$uidSuffix")
    }

}
