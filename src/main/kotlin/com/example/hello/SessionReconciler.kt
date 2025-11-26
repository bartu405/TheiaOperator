package com.example.hello

import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.*
import io.javaoperatorsdk.operator.api.reconciler.Cleaner
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import controllerOwnerRef
import java.io.ByteArrayInputStream
import org.slf4j.LoggerFactory


@ControllerConfiguration(
    name = "session-controller",
)
class SessionReconciler(
    private val client: KubernetesClient
) : Reconciler<Session> {

    // Hostname used in Ingress + status.url.
    // For real environments you can change this per cluster / env.
    val ingressHost: String = System.getenv("INGRESS_HOST") ?: "theia.localtest.me"


    private val log = LoggerFactory.getLogger(SessionReconciler::class.java)

    override fun reconcile(resource: Session, context: Context<Session>): UpdateControl<Session> {
        val ns = resource.metadata?.namespace ?: "default"
        val sessionName = resource.metadata?.name
        val workspaceName = resource.spec?.workspaceName
        val appDefName = resource.spec?.appDefinitionName
        val envVarsFromConfigMaps: List<String> = resource.spec?.envVarsFromConfigMaps ?: emptyList()
        val envVarsFromSecrets: List<String> = resource.spec?.envVarsFromSecrets ?: emptyList()


        log.info(
            "Reconciling Session {}/{} workspaceName={} appDefinitionName={}",
            ns, sessionName, workspaceName, appDefName
        )

        // --- 1) Basic validation: spec must contain workspaceName + appDefinitionName
        if (sessionName.isNullOrBlank() ||
            workspaceName.isNullOrBlank() ||
            appDefName.isNullOrBlank()
        ) {
            log.warn(
                "Session {}/{} is missing sessionName, workspaceName or appDefinitionName, skipping",
                ns, sessionName
            )

            val status = ensureStatus(resource)
            status.ready = false
            status.url = null
            status.message = "Missing sessionName, workspaceName or appDefinitionName"

            return UpdateControl.patchStatus(resource)
        }

        // --- 1.5) Rule: only ONE Session per Workspace
        // TODO: eski sessionu veya yeni sessionu silsek mi?????
        val otherSessions = client.resources(Session::class.java)
            .inNamespace(ns)
            .list()
            .items
            .filter { it.metadata?.name != sessionName && it.spec?.workspaceName == workspaceName }

        if (otherSessions.isNotEmpty()) {
            val existing = otherSessions.first()
            log.warn(
                "Session {}/{} cannot start because workspace '{}' already has another session '{}'",
                ns,
                sessionName,
                workspaceName,
                existing.metadata?.name
            )

            val status = ensureStatus(resource)
            status.ready = false
            status.url = null
            status.message =
                "Workspace '$workspaceName' already has an active session: '${existing.metadata?.name}'"

            return UpdateControl.patchStatus(resource)
        }

        // --- 2) Load AppDefinition to get image
        val appDef = client.resources(AppDefinition::class.java)
            .inNamespace(ns)
            .withName(appDefName)
            .get()

        if (appDef == null) {
            log.warn(
                "AppDefinition {}/{} not found for Session {}/{}",
                ns, appDefName, ns, sessionName
            )

            val status = ensureStatus(resource)
            status.ready = false
            status.url = null
            status.message = "AppDefinition '$appDefName' not found in namespace '$ns'"

            return UpdateControl.patchStatus(resource)
        }

        // --- 2.5) Check Workspace existence + readiness
        val workspace = client.resources(Workspace::class.java)
            .inNamespace(ns)
            .withName(workspaceName)
            .get()

        if (workspace == null) {
            val status = ensureStatus(resource)
            status.ready = false
            status.url = null
            status.message = "Workspace '$workspaceName' not found in namespace '$ns'"
            return UpdateControl.patchStatus(resource)
        }

        // if Workspace exists but is NOT ready
        if (workspace.status?.ready != true) {
            val status = ensureStatus(resource)
            status.ready = false
            status.url = null
            status.message =
                "Workspace '$workspaceName' is not ready yet (status.ready=${workspace.status?.ready})"
            return UpdateControl.patchStatus(resource)
        }

        // after workspace checks
        val sessionMeta = resource.metadata
        val existingRefs = sessionMeta.ownerReferences ?: emptyList()

        val hasWsOwner = existingRefs.any { it.uid == workspace.metadata.uid }

        if (!hasWsOwner) {
            val newRefs = existingRefs.toMutableList()
            newRefs.add(controllerOwnerRef(workspace))
            sessionMeta.ownerReferences = newRefs

            // patch Session metadata so the ownerRef is stored in the cluster
            client.resource(resource).inNamespace(ns).patch()
        }


        // --- 2.6) Consistency check: Workspace.appDefinitionName vs Session.appDefinitionName

        val workspaceAppDefName = workspace.spec?.appDefinitionName

        if (!workspaceAppDefName.isNullOrBlank() && !appDefName.isNullOrBlank() && workspaceAppDefName != appDefName) {
            log.warn(
                "Session {}/{} refers to appDefinition '{}' but workspace '{}' refers to '{}'",
                ns,
                sessionName,
                appDefName,
                workspaceName,
                workspaceAppDefName
            )

            val status = ensureStatus(resource)
            status.ready = false
            status.url = null
            status.message =
                "Workspace '$workspaceName' uses appDefinition '$workspaceAppDefName' but Session refers to '$appDefName'"

            return UpdateControl.patchStatus(resource)
        }



        // --- 3) Determine image, resources, env

        val appSpec = appDef.spec

        val image = appSpec.image



        val port = appSpec.port

        // --- Requests
        val requestsCpu = appSpec?.requestsCpu ?: "250m"
        val requestsMemory = appSpec?.requestsMemory ?: "512Mi"

        // --- Limits: either explicit, or same as requests
        val limitsCpu = appSpec?.limitsCpu ?: requestsCpu
        val limitsMemory = appSpec?.limitsMemory ?: requestsMemory


        val mountPath = appSpec?.mountPath ?: "/home/project"

        // 1) Start from app-level env list (EnvVarSpec)
        val appEnvList: List<EnvVarSpec> = appSpec?.env ?: emptyList()

        // 2) Add/override with session-level envVars (Map<String, String>)
        val sessionEnvMap: Map<String, String> = resource.spec?.envVars ?: emptyMap()

        // Merge: app env first, then session overrides/appends
        val mergedEnvByName = LinkedHashMap<String, String?>()

        // app-level env
        for (envVar in appEnvList) {
            mergedEnvByName[envVar.name] = envVar.value
        }

        // session-level env (override or add)
        for ((name, value) in sessionEnvMap) {
            mergedEnvByName[name] = value
        }

        // Convert back to List<EnvVarSpec> for the template
        val mergedEnv: List<EnvVarSpec> =
            mergedEnvByName.map { (name, value) ->
                EnvVarSpec().apply {
                    this.name = name
                    this.value = value
                }
            }



        val nonNullSessionName = sessionName
        val nonNullWorkspaceName = workspaceName

        log.info(
            "DEBUG: mergedEnv for Session {}/{} -> size={} values={}",
            ns,
            sessionName,
            mergedEnv.size,
            mergedEnv.joinToString { "${it.name}=${it.value}" }
        )


        // --- 3) Ensure Deployment + Service + Ingress
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


        ensureTheiaService(ns, nonNullSessionName, port, owner = resource)
        ensureTheiaIngress(ns, nonNullSessionName, port, owner = resource)

        // --- 4) Update Session status as "ready" with external-ish URL
        val status = ensureStatus(resource)
        val url = "http://$ingressHost/s/$nonNullSessionName/"

        status.ready = true
        status.url = url
        status.message = "Session is running"

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
        env: List<EnvVarSpec>,
        envVarsFromConfigMaps: List<String>,
        envVarsFromSecrets: List<String>,
        port: Int,
        mountPath: String,
        owner: Session
    )
    {
        val deploymentName = "theia-$sessionName"
        val pvcName = "workspace-$workspaceName"

        log.info("Ensuring Deployment {} in ns {} using PVC {}", deploymentName, namespace, pvcName)

        // Render the template
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
                "mountPath" to mountPath
            )
        )


        log.info("DEBUG: rendered theia Deployment YAML:\n{}", yaml)

        // Convert YAML string into InputStream
        val inputStream = ByteArrayInputStream(yaml.toByteArray(Charsets.UTF_8))

        // Load YAML into Kubernetes resources using Fabric8
        val resources = client.load(inputStream).items()

        // kubectl apply -f theia-deployment.yaml
        resources.forEach { res ->
            val meta = res.metadata
            meta.ownerReferences = listOf(controllerOwnerRef(owner))
            client.resource(res).inNamespace(namespace).createOrReplace()
        }

        log.info("Ensured Deployment {}/{} from template", namespace, deploymentName)
    }



    private fun ensureTheiaService(
        namespace: String,
        sessionName: String,
        port: Int,
        owner: Session
    ) {
        val serviceName = "theia-$sessionName"

        log.info("Ensuring Service {} in ns {}", serviceName, namespace)

        val serviceType = "ClusterIP"
        val servicePort = port
        val targetPort = port

        // 1) Render YAML from Velocity template
        val yaml = TemplateRenderer.render(
            "templates/theia-service.yaml.vm",
            mapOf(
                "namespace" to namespace,
                "serviceName" to serviceName,
                "sessionName" to sessionName,
                "serviceType" to serviceType,
                "servicePort" to servicePort,
                "targetPort" to targetPort
            )
        )

        // 2) Load the rendered YAML into Fabric8 and create/replace it
        val inputStream = ByteArrayInputStream(yaml.toByteArray(Charsets.UTF_8))
        val resources = client.load(inputStream).items()

        // kubectl apply -f theia-service.yaml
        resources.forEach { res ->
            res.metadata.ownerReferences = listOf(controllerOwnerRef(owner))
            client.resource(res).inNamespace(namespace).createOrReplace()
        }

        log.info("Ensured Service {}/{} from template", namespace, serviceName)
    }

    private fun ensureTheiaIngress(
        namespace: String,
        sessionName: String,
        port: Int,
        owner: Session
    ) {
        val ingressName = "theia-$sessionName"
        val serviceName = "theia-$sessionName"
        val servicePort = port

        log.info("Ensuring Ingress {} in ns {} for host {} and path /s/{}/", ingressName, namespace, ingressHost, sessionName)

        val yaml = TemplateRenderer.render(
            "templates/theia-ingress.yaml.vm",
            mapOf(
                "namespace" to namespace,
                "ingressName" to ingressName,
                "sessionName" to sessionName,
                "serviceName" to serviceName,
                "servicePort" to servicePort,
                "host" to ingressHost
            )
        )


        val inputStream = ByteArrayInputStream(yaml.toByteArray(Charsets.UTF_8))
        val resources = client.load(inputStream).items()

        // kubectl apply -f theia-ingress.yaml
        resources.forEach { res ->
            res.metadata.ownerReferences = listOf(controllerOwnerRef(owner))
            client.resource(res).inNamespace(namespace).createOrReplace()
        }

        log.info("Ensured Ingress {}/{} from template", namespace, ingressName)
    }


    private fun ensureStatus(resource: Session): SessionStatus {
        if (resource.status == null) {
            resource.status = SessionStatus()
        }
        return resource.status!!
    }




}