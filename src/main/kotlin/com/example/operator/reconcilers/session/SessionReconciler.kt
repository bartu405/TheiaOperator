// File: SessionReconciler.kt
package com.example.operator.reconcilers.session

import com.example.operator.AppDefinition
import com.example.operator.Session
import com.example.operator.SessionStatus
import com.example.operator.Workspace
import com.example.operator.config.OperatorConfig
import com.example.operator.naming.SessionNaming
import com.example.operator.naming.Labeling
import com.example.operator.utils.OwnerRefs
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.Cleaner
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import org.slf4j.LoggerFactory
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
        private const val SERVICE_PORT_NAME = "http"
    }

    private val log = LoggerFactory.getLogger(SessionReconciler::class.java)

    private val resourceManager = SessionResources(client, config)
    private val ingressManager = SessionIngress(client, config)

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
            return failStatus(resource, "spec is null on Session $ns/$k8sName")
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

        val nonNullSessionName = sessionName
        val nonNullWorkspaceName = workspaceName
        val nonNullAppDefName = appDefName

        log.info(
            "Reconciling logical Session name='{}' workspace='{}' appDefinition='{}' user='{}'",
            nonNullSessionName, nonNullWorkspaceName, nonNullAppDefName, user
        )

        // --- 2) Only one *active* Session per Workspace
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

        // --- 3) Optional: max sessions per user
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

        // --- 4) Load AppDefinition
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

        val appPort = port
        val monitorPort = appSpec.monitor?.port
        val hasActivityTracker = appSpec.monitor?.activityTracker != null
        val activityTracker = appSpec.monitor?.activityTracker
        val activityTimeout = activityTracker?.timeoutAfter
        val activityNotifyAfter = activityTracker?.notifyAfter

        val imagePullPolicy = appSpec.imagePullPolicy ?: "IfNotPresent"
        val pullSecret = appSpec.pullSecret
        val downlinkLimit = appSpec.downlinkLimit
        val uplinkLimit = appSpec.uplinkLimit

        // --- 5) Workspace existence
        val workspace = client.resources(Workspace::class.java)
            .inNamespace(ns)
            .withName(nonNullWorkspaceName)
            .get()
            ?: return failStatus(resource, "Workspace '$nonNullWorkspaceName' not found in '$ns'")

        // --- 6) Workspace must have a storage PVC name
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

        // --- 7) ownerRef
        val existingRefs = (meta.ownerReferences ?: emptyList()).toMutableList()
        val newRefs = existingRefs
            .filterNot { it.controller == true }
            .toMutableList()
        newRefs.add(OwnerRefs.controllerOwnerRef(workspace))
        meta.ownerReferences = newRefs
        metadataChanged = true

        // --- 8) Session and Workspace must agree on appDefinition
        val wsAppDefName = workspace.spec?.appDefinition
        if (!wsAppDefName.isNullOrBlank() && wsAppDefName != nonNullAppDefName) {
            return failStatus(
                resource,
                "Workspace '$nonNullWorkspaceName' uses appDefinition '$wsAppDefName' but session uses '$nonNullAppDefName'"
            )
        }

        // --- 9) labels
        val labels = (meta.labels ?: emptyMap()).toMutableMap()
        fun putIfMissing(k: String, v: String?) {
            if (!v.isNullOrBlank() && !labels.containsKey(k)) {
                labels[k] = v
                metadataChanged = true
            }
        }

        putIfMissing("app.henkan.io/workspaceName", Labeling.toLabelValue(nonNullWorkspaceName))
        putIfMissing("app.henkan.io/workspaceUser", Labeling.toLabelValue(user))
        putIfMissing("app.henkan.io/henkanProjectName", Labeling.toLabelValue(workspace.spec?.label))
        meta.labels = labels

        // --- 10) Build environment variables
        val sessionUid = resource.metadata?.uid
            ?: return failStatus(resource, "Session UID is missing")

        val sessionBaseName = SessionNaming.sessionResourceBaseName(user, nonNullAppDefName, sessionUid)
        val serviceName = sessionBaseName
        val serviceUrl = "http://$serviceName:$port"

        val sessionUrlForEnv = "${ingressScheme}://$ingressHost/$sessionUid/"
        val sessionUrlForStatus = "$ingressHost/$sessionUid/"

        val mergedEnv = buildEnvironmentVariables(
            appSpec = appSpec,
            appDef = appDef,
            nonNullAppDefName = nonNullAppDefName,
            serviceUrl = serviceUrl,
            sessionUid = sessionUid,
            nonNullSessionName = nonNullSessionName,
            user = user,
            sessionUrlForEnv = sessionUrlForEnv,
            sessionSecret = sessionSecret,
            monitorPort = monitorPort,
            hasActivityTracker = hasActivityTracker,
            activityTimeout = activityTimeout,
            activityNotifyAfter = activityNotifyAfter,
            spec = spec,
            ns = ns
        )

        // --- 11) Resources from AppDefinition
        val requestsCpu = appSpec.requestsCpu ?: "250m"
        val requestsMemory = appSpec.requestsMemory ?: "512Mi"
        val limitsCpu = appSpec.limitsCpu ?: requestsCpu
        val limitsMemory = appSpec.limitsMemory ?: requestsMemory
        val mountPath = appSpec.mountPath ?: "/home/project"

        val defaultUid = 101
        val runAsUid = appSpec.uid ?: defaultUid
        val fsGroupUid = appSpec.uid ?: defaultUid

        val appLabel = Labeling.toLabelValue("${nonNullSessionName}-${sessionUid}")
            ?: "${nonNullSessionName}-${sessionUid}"

        // --- 12) Check for ingress existence
        val ingressName = ingressManager.ingressNameForAppDef(appDef)
        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val existingIngress = ingressClient.withName(ingressName).get()
        if (existingIngress == null) {
            return failStatus(resource, "Shared Ingress '$ingressName' is missing. Create it first.")
        }



        // --- 13) Create all resources
        val sessionProxyCm = resourceManager.ensureSessionProxyConfigMap(
            namespace = ns,
            user = user,
            appDefName = nonNullAppDefName,
            sessionName = nonNullSessionName,
            sessionUid = sessionUid,
            port = appPort,
            owner = resource
        )

        val sessionEmailCm = resourceManager.ensureSessionEmailConfigMap(
            namespace = ns,
            user = user,
            appDefName = nonNullAppDefName,
            sessionName = nonNullSessionName,
            sessionUid = sessionUid,
            owner = resource
        )

        resourceManager.ensureTheiaDeployment(
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
            appLabel = appLabel
        )

        resourceManager.ensureTheiaService(
            namespace = ns,
            serviceName = serviceName,
            sessionName = nonNullSessionName,
            port = appPort,
            owner = resource,
            appLabel = appLabel,
            appDefinitionName = nonNullAppDefName,
            user = user
        )

        ingressManager.ensureSharedIngressForSession(
            session = resource,
            serviceName = serviceName,
            ingressName = ingressName,
            appDef = appDef,
            port = appPort
        )

        // --- 14) Status on success
        val status = ensureStatus(resource)
        status.operatorStatus = "HANDLED"
        status.operatorMessage = "Session is running"
        status.url = sessionUrlForStatus
        status.lastActivity = System.currentTimeMillis()

        return if (metadataChanged) {
            UpdateControl.patchResourceAndStatus(resource)
        } else {
            UpdateControl.patchStatus(resource)
        }
    }

    override fun cleanup(resource: Session, context: Context<Session>): DeleteControl {
        val ns = resource.metadata?.namespace ?: "default"
        val name = resource.metadata?.name ?: "<no-name>"

        log.info("Running cleanup for Session {}/{}", ns, name)

        // Delegate ingress cleanup to the IngressManager
        ingressManager.removeSessionFromIngress(resource)

        return DeleteControl.defaultDelete()
    }

    private fun buildEnvironmentVariables(
        appSpec: com.example.operator.AppDefinitionSpec,
        appDef: AppDefinition,
        nonNullAppDefName: String,
        serviceUrl: String,
        sessionUid: String,
        nonNullSessionName: String,
        user: String,
        sessionUrlForEnv: String,
        sessionSecret: String?,
        monitorPort: Int?,
        hasActivityTracker: Boolean,
        activityTimeout: Int?,
        activityNotifyAfter: Int?,
        spec: com.example.operator.SessionSpec,
        ns: String
    ): List<EnvVar> {
        val sessionEnvMap = spec.envVars ?: emptyMap()
        val appId = config.appId ?: appSpec.name ?: appDef.metadata?.name ?: nonNullAppDefName

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

        return mergedEnv
    }

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
}