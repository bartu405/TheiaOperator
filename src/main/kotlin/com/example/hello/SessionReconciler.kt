// File: SessionReconciler.kt
package com.example.hello

import controllerOwnerRef
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.Cleaner
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

@ControllerConfiguration(
    name = "session-controller",
    finalizerName = SessionReconciler.FINALIZER_NAME
)
class SessionReconciler(
    private val client: KubernetesClient
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

    private val ingressHost: String = System.getenv("INGRESS_HOST") ?: "theia.localtest.me"
    private val log = LoggerFactory.getLogger(SessionReconciler::class.java)

    // Optional limit like Henkan's --sessionsPerUser
    private val sessionsPerUser: Int? = System.getenv("SESSIONS_PER_USER")?.toIntOrNull()

    private val keycloakUrl: String? = System.getenv("THEIACLOUD_KEYCLOAK_URL")
    private val keycloakRealm: String? = System.getenv("THEIACLOUD_KEYCLOAK_REALM")
    private val keycloakClientId: String? = System.getenv("THEIACLOUD_KEYCLOAK_CLIENT_ID")

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

        val status = ensureStatus(resource)

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

        // --- 2.5.1) Workspace must have a storage PVC name
        val workspacePvcName = workspace.spec?.storage
        if (workspacePvcName.isNullOrBlank()) {
            return failStatus(
                resource,
                "Workspace '$nonNullWorkspaceName' has no spec.storage (PVC name)"
            )
        }

        // --- 2.6) Ensure Session has ownerRef = Workspace
        val existingRefs = meta.ownerReferences ?: emptyList()
        val hasWsOwner = existingRefs.any { it.uid == workspace.metadata.uid }

        if (!hasWsOwner) {
            val newRefs = existingRefs.toMutableList()
            newRefs.add(controllerOwnerRef(workspace))
            meta.ownerReferences = newRefs
            client.resource(resource).inNamespace(ns).patch()
            log.info(
                "Added ownerReference Workspace {}/{} to Session {}/{}",
                ns, workspace.metadata.name, ns, k8sName
            )
        }

        // --- 2.7) Session and Workspace must agree on appDefinition
        val wsAppDefName = workspace.spec?.appDefinition
        if (!wsAppDefName.isNullOrBlank() && wsAppDefName != nonNullAppDefName) {
            return failStatus(
                resource,
                "Workspace '$nonNullWorkspaceName' uses appDefinition '$wsAppDefName' but session uses '$nonNullAppDefName'"
            )
        }

        // --- 2.8) Henkan-style labels on Session CR itself
        val sessMeta = resource.metadata!!
        val sessLabels = (sessMeta.labels ?: mutableMapOf()).toMutableMap()

        val existingWsNameLabel  = sessLabels["app.henkan.io/workspaceName"]
        val existingUserLabel    = sessLabels["app.henkan.io/workspaceUser"]
        val existingProjectLabel = sessLabels["app.henkan.io/henkanProjectName"]

        val wsSpec = workspace.spec
        val projectNameRaw = wsSpec?.label
        val workspaceNameLabel = toHenkanLabelValue(nonNullWorkspaceName)
        val workspaceUserLabel = toHenkanLabelValue(user)
        val projectNameLabel = toHenkanLabelValue(projectNameRaw)

        if (existingWsNameLabel == null && !workspaceNameLabel.isNullOrBlank()) {
            sessLabels["app.henkan.io/workspaceName"] = workspaceNameLabel
        }
        if (existingUserLabel == null && !workspaceUserLabel.isNullOrBlank()) {
            sessLabels["app.henkan.io/workspaceUser"] = workspaceUserLabel
        }
        if (existingProjectLabel == null && !projectNameLabel.isNullOrBlank()) {
            sessLabels["app.henkan.io/henkanProjectName"] = projectNameLabel
        }

        sessMeta.labels = sessLabels
        client.resource(resource).inNamespace(ns).patch()

        log.info(
            "Session {}/{} labeled with Henkan labels: {}",
            ns, k8sName, sessLabels.filterKeys { it.startsWith("app.henkan.io/") }
        )

        // --- 3) Env: system THEIACLOUD_* + user envVars
        val sessionEnvMap = spec.envVars ?: emptyMap()

        val appId = appSpec.uid?.toString() ?: nonNullAppDefName
        val serviceName = "theia-$nonNullSessionName"
        val serviceUrl = "http://$serviceName:$port"

        // Use the Kubernetes UID of the Session as the path segment
        val sessionUid = resource.metadata?.uid ?: ""

        // Henkan-like URLs:
        val sessionUrlForEnv = "http://$ingressHost/$sessionUid/"
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

        // --- 5) Ensure Deployment + Service + SHARED Ingress
        ensureTheiaDeployment(
            ns,
            nonNullSessionName,
            nonNullWorkspaceName,
            image,
            imagePullPolicy,
            pullSecret,
            requestsCpu,
            requestsMemory,
            limitsCpu,
            limitsMemory,
            mergedEnv,
            envVarsFromConfigMaps,
            envVarsFromSecrets,
            port,
            monitorPort,
            mountPath,
            fsGroupUid,
            runAsUid,
            downlinkLimit,
            uplinkLimit,
            appDefinitionName = nonNullAppDefName,
            user = user,
            sessionUid = sessionUid,
            owner = resource,
            pvcName = workspacePvcName,
        )

        ensureTheiaService(
            namespace = ns,
            sessionName = nonNullSessionName,
            port = port,
            owner = resource,
            monitorPort = monitorPort,
            appLabel = "theia",
            appDefinitionName = nonNullAppDefName,
            user = user,
            sessionUid = sessionUid
        )

        val ingressName = ingressNameForAppDef(appDef)
        ensureSharedIngressForSession(
            session = resource,
            serviceName = serviceName,
            ingressName = ingressName,
            appDef = appDef
        )


        // --- 6) Status on success
        val nowMillis = System.currentTimeMillis()

        status.operatorStatus = "HANDLED"
        status.operatorMessage = "Session is running"
        status.url = sessionUrlForStatus
        status.error = null
        status.lastActivity = nowMillis

        return UpdateControl.patchStatus(resource)
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

        val updatedIngress = IngressBuilder(ingress)
            .editOrNewSpec()
            .withRules(newRules)
            .endSpec()
            .build()

        ingressClient.resource(updatedIngress).createOrReplace()

        return DeleteControl.defaultDelete()
    }



    // === Helpers for URL/paths =============================================================

    private fun sessionHost(@Suppress("UNUSED_PARAMETER") session: Session): String =
        ingressHost

    private fun sessionPath(session: Session): String =
        "/${session.metadata?.uid}/"

    // === Status & error handling ===========================================================

    private fun failStatus(resource: Session, msg: String): UpdateControl<Session> {
        val status = ensureStatus(resource)
        status.operatorStatus = "Error"
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
    ) {
        val deploymentName = "theia-$sessionName"

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
                "oauth2ProxyImage" to "quay.io/oauth2-proxy/oauth2-proxy:v7.6.0",
                "oauth2ProxyConfigMapName" to "theia-oauth2-proxy-config",
                "oauth2TemplatesConfigMapName" to "oauth2-templates",
                "oauth2EmailsConfigMapName" to "theia-oauth2-emails",
                "downlinkLimit" to downlinkLimit,
                "uplinkLimit" to uplinkLimit,
                "appDefinitionName" to appDefinitionName,
                "user" to user,
                "sessionUid" to sessionUid
            )
        )

        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            r.metadata.ownerReferences = listOf(controllerOwnerRef(owner))
            client.resource(r).inNamespace(namespace).createOrReplace()
        }
    }

    private fun ensureTheiaService(
        namespace: String,
        sessionName: String,
        port: Int,
        owner: Session,
        monitorPort: Int? = null,
        appLabel: String = "theia",
        appDefinitionName: String,
        user: String,
        sessionUid: String,
    ) {
        val yaml = TemplateRenderer.render(
            "templates/theia-service.yaml.vm",
            mapOf(
                "namespace" to namespace,
                "serviceName" to "theia-$sessionName",
                "sessionName" to sessionName,
                "serviceType" to "ClusterIP",
                "servicePort" to port,
                "targetPort" to port,
                "monitorPort" to monitorPort,
                "appLabel" to appLabel,
                "appDefinitionName" to appDefinitionName,
                "user" to user,
                "sessionUid" to sessionUid,
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
        appDef: AppDefinition
    ) {
        val ns = session.metadata?.namespace ?: "default"
        val host = sessionHost(session)
        val path = sessionPath(session)

        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val existing = ingressClient.withName(ingressName).get()

        if (existing == null) {
            log.info(
                "Shared Ingress '{}' not found in {}, creating new with path {} -> service {}",
                ingressName, ns, path, serviceName
            )

            val ingress = IngressBuilder()
                .withNewMetadata()
                .withName(ingressName)
                .addToLabels("app", "theia")
                // IMPORTANT: owner is the AppDefinition now
                .withOwnerReferences(controllerOwnerRef(appDef))
                .endMetadata()
                .withNewSpec()
                .addNewRule()
                .withHost(host)
                .withNewHttp()
                .addNewPath()
                .withPath(path)
                .withPathType("Prefix")
                .withNewBackend()
                .withNewService()
                .withName(serviceName)
                .withNewPort().withName(SERVICE_PORT_NAME).endPort()
                .endService()
                .endBackend()
                .endPath()
                .endHttp()
                .endRule()
                .endSpec()
                .build()

            ingressClient.resource(ingress).createOrReplace()
            return
        }

        // Update existing shared Ingress for this AppDefinition
        val ingress = IngressBuilder(existing).build()
        val rules = ingress.spec?.rules?.toMutableList() ?: mutableListOf()

        val ruleIndex = rules.indexOfFirst { it.host == host }
        if (ruleIndex == -1) {
            log.info(
                "Adding new host rule {} with path {} -> {} to Ingress '{}'",
                host, path, serviceName, ingressName
            )
            val newRule = IngressRuleBuilder()
                .withHost(host)
                .withNewHttp()
                .addNewPath()
                .withPath(path)
                .withPathType("Prefix")
                .withNewBackend()
                .withNewService()
                .withName(serviceName)
                .withNewPort().withName(SERVICE_PORT_NAME).endPort()
                .endService()
                .endBackend()
                .endPath()
                .endHttp()
                .build()
            rules.add(newRule)
        } else {
            val rule = rules[ruleIndex]
            val http = rule.http
            val paths = http?.paths?.toMutableList() ?: mutableListOf()
            val existingPathIndex = paths.indexOfFirst { it.path == path }

            if (existingPathIndex == -1) {
                log.info(
                    "Adding new path {} -> {} under host {} to Ingress '{}'",
                    path, serviceName, host, ingressName
                )
                val newPath = HTTPIngressPathBuilder()
                    .withPath(path)
                    .withPathType("Prefix")
                    .withNewBackend()
                    .withNewService()
                    .withName(serviceName)
                    .withNewPort().withName(SERVICE_PORT_NAME).endPort()
                    .endService()
                    .endBackend()
                    .build()
                paths.add(newPath)
            } else {
                log.info(
                    "Updating existing path {} under host {} in Ingress '{}' to point to {}",
                    path, host, ingressName, serviceName
                )
                val existingPath = paths[existingPathIndex]
                existingPath.backend?.service?.name = serviceName
                existingPath.backend?.service?.port?.name = SERVICE_PORT_NAME
            }

            // IMPORTANT: rebuild the rule so that http is created if it was null
            val newRule = IngressRuleBuilder(rule)
                .editOrNewHttp()
                .withPaths(paths)
                .endHttp()
                .build()

            rules[ruleIndex] = newRule
        }

        ingress.spec?.setRules(rules)
        ingressClient.resource(ingress).createOrReplace()

    }

}
