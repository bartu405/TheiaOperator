package com.globalmaksimum.operator.reconcilers.session

import com.globalmaksimum.operator.Session
import com.globalmaksimum.operator.config.OperatorConfig
import com.globalmaksimum.operator.naming.SessionNaming
import com.globalmaksimum.operator.utils.TemplateRenderer
import com.globalmaksimum.operator.utils.OwnerRefs
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

/**
 * Manages the creation and updates of Kubernetes resources for Sessions.
 *
 * Responsibilities:
 * 1. Create Deployments for sessions (main container + OAuth2 proxy sidecar)
 *    - Deployments are created once and treated as immutable
 * 2. Create Services (ClusterIP for internal routing)
 *    - Services are created if missing
 * 3. Create and update ConfigMaps
 *    - OAuth2 proxy configuration
 *    - Authenticated users list
 */
class SessionResources(
    private val client: KubernetesClient,
    private val config: OperatorConfig
) {
    private val log = LoggerFactory.getLogger(SessionResources::class.java)

    fun ensureTheiaDeployment(
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
        log.info("Ensuring Deployment {} in ns {} using PVC {}", deploymentName, namespace, pvcName)

        // ============================================================
        // SECTION 1: CHECK IF DEPLOYMENT EXISTS
        // ============================================================

        val existing = client.apps().deployments()
            .inNamespace(namespace)
            .withName(deploymentName)
            .get()

        if (existing != null) {
            log.debug("Deployment {} already exists, skipping", deploymentName)
            return
        }


        // ============================================================
        // SECTION 2: CREATE DEPLOYMENT ONLY IF MISSING
        // NOTE: Deployments are intentionally treated as immutable once created.
        // Any spec changes require session recreation.
        // ============================================================

        log.info("Creating Deployment {}", deploymentName)

        val yaml = TemplateRenderer.render(
            "templates/deployment.yaml.vm",
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
                "oauth2ProxyVersion" to config.oAuth2ProxyVersion,
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

        // Parse YAML into Kubernetes resources
        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            // Set Session as owner
            r.metadata.ownerReferences = listOf(OwnerRefs.controllerOwnerRef(owner))
            client.resource(r).inNamespace(namespace).create()
        }

        log.info("Created Deployment {}", deploymentName)
    }

    fun ensureTheiaService(
        namespace: String,
        serviceName: String,
        sessionName: String,
        port: Int,
        owner: Session,
        appLabel: String,
        appDefinitionName: String,
        user: String,
    ) {

        // ============================================================
        // SECTION 1: CHECK IF SERVICE EXISTS
        // ============================================================

        val existing = client.services()
            .inNamespace(namespace)
            .withName(serviceName)
            .get()

        if (existing != null) {
            log.info("Service {} already exists in namespace {}", serviceName, namespace)
            return
        }

        // ============================================================
        // SECTION 2: CREATE SERVICE ONLY IF MISSING
        // ============================================================

        log.info("Creating Service {} in namespace {}", serviceName, namespace)

        val yaml = TemplateRenderer.render(
            "templates/service.yaml.vm",
            mapOf(
                "namespace" to namespace,
                "serviceName" to serviceName,
                "sessionName" to sessionName,
                "serviceType" to "ClusterIP",
                "servicePort" to port,
                "targetPort" to "web",
                "appLabel" to appLabel,
                "appDefinitionName" to appDefinitionName,
                "user" to user,
            )
        )


        // Parse YAML into Kubernetes resources
        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            // Set Session as owner for cascading deletion
            r.metadata.ownerReferences = listOf(OwnerRefs.controllerOwnerRef(owner))
            client.resource(r).inNamespace(namespace).create()
        }

        log.info("Created Service:\n{}", serviceName)
    }

    fun ensureSessionProxyConfigMap(
        namespace: String,
        user: String,
        appDefName: String,
        sessionName: String,
        sessionUid: String,
        port: Int,
        owner: Session
    ): String {

        // ============================================================
        // SECTION 1: CHECK IF CONFIGMAP EXISTS AND PRESERVE LABELS
        // ============================================================

        val cmName = SessionNaming.sessionProxyCmName(user, appDefName, sessionUid)

        val existingCm = client.configMaps()
            .inNamespace(namespace)
            .withName(cmName)
            .get()

        val labels = existingCm?.metadata?.labels?.toMutableMap() ?: mutableMapOf()

        fun putIfMissing(k: String, v: String?) {
            if (!v.isNullOrBlank() && !labels.containsKey(k)) {
                labels[k] = v
            }
        }

        putIfMissing("app.kubernetes.io/component", "session")
        putIfMissing("app.kubernetes.io/part-of", "theia-cloud")
        putIfMissing("theia-cloud.io/app-definition", appDefName)
        putIfMissing("theia-cloud.io/session", sessionName)
        putIfMissing("theia-cloud.io/template-purpose", "proxy")
        putIfMissing("theia-cloud.io/user", user)

        if (existingCm != null) {
            log.info("ConfigMap {} already exists, merging labels", cmName)
            existingCm.metadata.labels = labels
            client.resource(existingCm).patch()
            return cmName
        }



        // ============================================================
        // SECTION 2: CALCULATE OAUTH2 CONFIGURATION VALUES
        // ============================================================

        val issuerUrl = "${config.keycloakUrl}realms/${config.keycloakRealm}"
        val host = config.instancesHost ?: "theia.localtest.me"
        val scheme = config.ingressScheme.ifBlank { "http" }

        val fullHost = "${host}/${sessionUid}"
        val redirectUrl = "${scheme}://${fullHost}/oauth2/callback"
        val upstreamUrl = "http://127.0.0.1:$port/"

        // ============================================================
        // SECTION 3: RENDER AND CREATE CONFIGMAP
        // ============================================================

        log.info("Creating ConfigMap {} in namespace {}", cmName, namespace)

        val model = mapOf(
            "configMapName" to cmName,
            "namespace" to namespace,
            "sessionUid" to sessionUid,
            "redirectUrl" to redirectUrl,
            "issuerUrl" to issuerUrl,
            "upstreamUrl" to upstreamUrl,
            "cookieDomain" to host,
        )

        val yaml = TemplateRenderer.render("templates/oauth2-proxy-config.yaml.vm", model)

        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            r.metadata.ownerReferences = listOf(OwnerRefs.controllerOwnerRef(owner))
            r.metadata.labels = labels
            client.resource(r).inNamespace(namespace).create()
        }

        log.info("Created ConfigMap {}", cmName)
        return cmName
    }

    fun ensureSessionEmailConfigMap(
        namespace: String,
        user: String,
        appDefName: String,
        sessionName: String,
        sessionUid: String,
        owner: Session
    ): String {

        // ============================================================
        // SECTION 1: CALCULATE CONFIGMAP NAME
        // ============================================================

        val name = SessionNaming.sessionEmailCmName(user, appDefName, sessionUid)

        // ============================================================
        // SECTION 2: PRESERVE EXISTING LABELS
        // ============================================================

        val existingCm = client.configMaps()
            .inNamespace(namespace)
            .withName(name)
            .get()

        val labels = existingCm?.metadata?.labels?.toMutableMap() ?: mutableMapOf()

        fun putIfMissing(k: String, v: String?) {
            if (!v.isNullOrBlank() && !labels.containsKey(k)) {
                labels[k] = v
            }
        }

        putIfMissing("app.kubernetes.io/component", "session")
        putIfMissing("app.kubernetes.io/part-of", "theia-cloud")
        putIfMissing("theia-cloud.io/app-definition", appDefName)
        putIfMissing("theia-cloud.io/session", sessionName)
        putIfMissing("theia-cloud.io/user", user)
        putIfMissing("theia-cloud.io/template-purpose", "emails")

        if (existingCm != null) {
            existingCm.metadata.labels = labels
            client.resource(existingCm).patch()
            return name
        }


        // ============================================================
        // SECTION 3: BUILD CONFIGMAP
        // ============================================================

        val cm = ConfigMapBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(namespace)
            .addToLabels(labels)
            .withOwnerReferences(OwnerRefs.controllerOwnerRef(owner))
            .endMetadata()
            .addToData("authenticated-emails-list", user)
            .build()

        // ============================================================
        // SECTION 4: APPLY CONFIGMAP
        // ============================================================

        client.configMaps().inNamespace(namespace).resource(cm).create()
        return name
    }
}