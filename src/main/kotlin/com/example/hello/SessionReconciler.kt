package com.example.hello

import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import org.slf4j.LoggerFactory
import controllerOwnerRef
import java.io.ByteArrayInputStream

@ControllerConfiguration(name = "session-controller")
class SessionReconciler(
    private val client: KubernetesClient
) : Reconciler<Session> {

    private val ingressHost: String = System.getenv("INGRESS_HOST") ?: "theia.localtest.me"
    private val log = LoggerFactory.getLogger(SessionReconciler::class.java)

    override fun reconcile(resource: Session, context: Context<Session>): UpdateControl<Session> {
        val ns = resource.metadata?.namespace ?: "default"
        val k8sName = resource.metadata?.name ?: "<no-name>"
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

        // --- 1.5) Only one Session per Workspace (by spec.workspace)
        val otherSessions = client.resources(Session::class.java)
            .inNamespace(ns)
            .list()
            .items
            .filter {
                it.metadata?.uid != resource.metadata?.uid &&
                        it.spec?.workspace == nonNullWorkspaceName
            }

        if (otherSessions.isNotEmpty()) {
            val existing = otherSessions.first()
            return failStatus(
                resource,
                "Workspace '$nonNullWorkspaceName' already has active session '${existing.spec?.name ?: existing.metadata?.name}'"
            )
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

        // --- 2.5) Workspace existence
        val workspace = client.resources(Workspace::class.java)
            .inNamespace(ns)
            .withName(nonNullWorkspaceName)
            .get()
            ?: return failStatus(resource, "Workspace '$nonNullWorkspaceName' not found in '$ns'")

        // --- 2.6) Ensure Session has ownerRef = Workspace
        val meta = resource.metadata
        val existingRefs = meta.ownerReferences ?: emptyList()
        val hasWsOwner = existingRefs.any { it.uid == workspace.metadata.uid }

        if (!hasWsOwner) {
            val newRefs = existingRefs.toMutableList()
            newRefs.add(controllerOwnerRef(workspace))
            meta.ownerReferences = newRefs
            client.resource(resource).inNamespace(ns).patch()
            log.info("Added ownerReference Workspace {}/{} to Session {}/{}", ns, workspace.metadata.name, ns, k8sName)
        }

        // --- 2.7) Session and Workspace must agree on appDefinition
        val wsAppDefName = workspace.spec?.appDefinition
        if (!wsAppDefName.isNullOrBlank() && wsAppDefName != nonNullAppDefName) {
            return failStatus(
                resource,
                "Workspace '$nonNullWorkspaceName' uses appDefinition '$wsAppDefName' but session uses '$nonNullAppDefName'"
            )
        }

        // --- 3) Env: system THEIACLOUD_* + user envVars

        val sessionEnvMap = spec.envVars ?: emptyMap()

        // Values derived from AppDefinition / Session / Ingress
        val appId = appSpec.uid?.toString() ?: nonNullAppDefName
        val serviceName = "theia-$nonNullSessionName"
        val serviceUrl = "http://$serviceName:$port"
        val sessionUrl = "http://$ingressHost/s/$nonNullSessionName/"
        val sessionUid = resource.metadata?.uid ?: ""

        // Start with system env vars (THEIACLOUD_*)
        val mergedEnv = mutableListOf<EnvVar>()

        mergedEnv += EnvVar("THEIACLOUD_APP_ID", appId, null)
        mergedEnv += EnvVar("THEIACLOUD_SERVICE_URL", serviceUrl, null)
        mergedEnv += EnvVar("THEIACLOUD_SESSION_UID", sessionUid, null)
        mergedEnv += EnvVar("THEIACLOUD_SESSION_NAME", nonNullSessionName, null)
        mergedEnv += EnvVar("THEIACLOUD_SESSION_USER", user, null)
        mergedEnv += EnvVar("THEIACLOUD_SESSION_URL", sessionUrl, null)

            // Now add user-defined envVars from Session.spec.envVars,
            // but don't let them override THEIACLOUD_* keys
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

        // --- 5) Ensure Deployment + Service + Ingress
        ensureTheiaDeployment(
            ns,
            nonNullSessionName,
            nonNullWorkspaceName,
            image,
            requestsCpu,
            requestsMemory,
            limitsCpu,
            limitsMemory,
            mergedEnv,
            envVarsFromConfigMaps,
            envVarsFromSecrets,
            port,
            mountPath,
            owner = resource
        )

        ensureTheiaService(
            ns,
            nonNullSessionName,
            port,
            owner = resource,
            monitorPort = null, // or some real metrics port
            appLabel = "theia"
        )

        ensureTheiaIngress(ns, nonNullSessionName, port, owner = resource)

        // --- 6) Status on success
        val nowSeconds = System.currentTimeMillis() / 1000

        status.operatorStatus = "Ready"
        status.operatorMessage = "Session is running"
        status.url = "http://$ingressHost/s/$nonNullSessionName/"
        status.error = null
        status.lastActivity = nowSeconds

        return UpdateControl.patchStatus(resource)
    }

    private fun failStatus(resource: Session, msg: String): UpdateControl<Session> {
        val status = ensureStatus(resource)
        status.operatorStatus = "Error"
        status.operatorMessage = msg
        status.error = msg
        status.url = null
        // lastActivity left as-is (it might represent last good activity)
        return UpdateControl.patchStatus(resource)
    }

    private fun ensureTheiaDeployment(
        namespace: String,
        sessionName: String,
        workspaceName: String,
        image: String,
        requestsCpu: String,
        requestsMemory: String,
        limitsCpu: String,
        limitsMemory: String,
        env: List<EnvVar>,
        envVarsFromConfigMaps: List<String>,
        envVarsFromSecrets: List<String>,
        port: Int,
        mountPath: String,
        owner: Session
    ) {
        val deploymentName = "theia-$sessionName"
        val pvcName = "workspace-$workspaceName"

        log.info("Ensuring Deployment {} in ns {} using PVC {}", deploymentName, namespace, pvcName)

        val yaml = TemplateRenderer.render(
            "templates/theia-deployment.yaml.vm",
            mapOf(
                "namespace" to namespace,
                "deploymentName" to deploymentName,
                "sessionName" to sessionName,
                "workspaceName" to workspaceName,
                "pvcName" to pvcName,
                "image" to image,
                "requestsCpu" to requestsCpu,
                "requestsMemory" to requestsMemory,
                "limitsCpu" to limitsCpu,
                "limitsMemory" to limitsMemory,
                "envs" to env,
                "envVarsFromConfigMaps" to envVarsFromConfigMaps,
                "envVarsFromSecrets" to envVarsFromSecrets,
                "port" to port,
                "mountPath" to mountPath,

                // new:
                "fsGroupUid" to 1000,
                "runAsUid" to 101,
                "oauth2ProxyImage" to "quay.io/oauth2-proxy/oauth2-proxy:v7.6.0",
                "oauth2ProxyConfigMapName" to "theia-oauth2-proxy-config",
                "oauth2TemplatesConfigMapName" to "oauth2-templates",
                "oauth2EmailsConfigMapName" to "theia-oauth2-emails"
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
        appLabel: String = "theia"
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
                "appLabel" to appLabel
            )
        )

        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            r.metadata.ownerReferences = listOf(controllerOwnerRef(owner))
            client.resource(r).inNamespace(namespace).createOrReplace()
        }

        log.info("Rendered Service YAML:\n{}", yaml)

    }


    private fun ensureTheiaIngress(
        namespace: String,
        sessionName: String,
        port: Int,
        owner: Session
    ) {
        val yaml = TemplateRenderer.render(
            "templates/theia-ingress.yaml.vm",
            mapOf(
                "namespace" to namespace,
                "ingressName" to "theia-$sessionName",
                "sessionName" to sessionName,
                "serviceName" to "theia-$sessionName",
                "servicePort" to port,
                "host" to ingressHost
            )
        )

        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            r.metadata.ownerReferences = listOf(controllerOwnerRef(owner))
            client.resource(r).inNamespace(namespace).createOrReplace()
        }
    }

    private fun ensureStatus(resource: Session): SessionStatus {
        if (resource.status == null) {
            resource.status = SessionStatus()
        }
        return resource.status!!
    }
}
