// File: SessionResources.kt
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
 * 1. Create/update Deployments (main container + OAuth2 proxy sidecar)
 * 2. Create/update Services (ClusterIP for internal routing)
 * 3. Create/update ConfigMaps (OAuth2 proxy config, authenticated emails list)
 *
 * Key Design:
 * - Uses Velocity templates for all resource YAML generation
 * - All resources have owner references (Session owns them for cascading deletion)
 * - ConfigMaps preserve labels set by Henkan-server (only add if missing)
 * - OAuth2 proxy runs as sidecar in same pod as IDE/application
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
        log.info(
            "Ensuring Deployment {} in ns {} using PVC {}",
            deploymentName, namespace, pvcName
        )

        // ============================================================
        // SECTION 1: RENDER DEPLOYMENT YAML FROM TEMPLATE
        // ============================================================

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

        // ============================================================
        // SECTION 2: LOAD AND APPLY
        // ============================================================

        // Parse YAML into Kubernetes resources
        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            // Set Session as owner
            r.metadata.ownerReferences = listOf(OwnerRefs.controllerOwnerRef(owner))
            client.resource(r).inNamespace(namespace).createOrReplace()
        }
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
        // SECTION 1: RENDER SERVICE YAML FROM TEMPLATE
        // ============================================================

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

        // ============================================================
        // SECTION 2: LOAD AND APPLY
        // ============================================================

        // Parse YAML into Kubernetes resources
        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            // Set Session as owner for cascading deletion
            r.metadata.ownerReferences = listOf(OwnerRefs.controllerOwnerRef(owner))
            client.resource(r).inNamespace(namespace).createOrReplace()
        }

        log.info("Rendered Service YAML:\n{}", yaml)
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
        // SECTION 1: CALCULATE OAUTH2 CONFIGURATION VALUES
        // ============================================================

        val issuerUrl = "${config.keycloakUrl}realms/${config.keycloakRealm}"
        val host = config.instancesHost ?: "theia.localtest.me"
        val scheme = config.ingressScheme.ifBlank { "http" }

        val fullHost = "${host}/${sessionUid}"
        val redirectUrl = "${scheme}://${fullHost}/oauth2/callback"
        val upstreamUrl = "http://127.0.0.1:$port/"

        val cmName = SessionNaming.sessionProxyCmName(user, appDefName, sessionUid)

        // ============================================================
        // SECTION 2: PRESERVE EXISTING LABELS
        // ============================================================
        val existingCm = client.configMaps()
            .inNamespace(namespace)
            .withName(cmName)
            .get()

        val labels = existingCm?.metadata?.labels?.toMutableMap() ?: mutableMapOf()

        // Add labels only if missing (don't override henkan-server's labels)
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

        // ============================================================
        // SECTION 3: RENDER CONFIGMAP YAML FROM TEMPLATE
        // ============================================================

        val model = mapOf(
            "configMapName" to cmName,
            "namespace" to namespace,
            "sessionUid" to sessionUid,
            "redirectUrl" to redirectUrl,
            "issuerUrl" to issuerUrl,
            "upstreamUrl" to upstreamUrl,
            "cookieDomain" to host,
        )

        val yaml = TemplateRenderer.render(
            "templates/oauth2-proxy-config.yaml.vm",
            model
        )

        // ============================================================
        // SECTION 4: LOAD AND APPLY
        // ============================================================

        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
            r.metadata.ownerReferences = listOf(OwnerRefs.controllerOwnerRef(owner))
            r.metadata.labels = labels  // Just use the merged labels directly
            client.resource(r).inNamespace(namespace).createOrReplace()
        }

        log.info("Ensured Session Proxy ConfigMap: {}", cmName)
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

        // Add labels only if missing (don't override henkan-server's labels)
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

        // ============================================================
        // SECTION 3: BUILD CONFIGMAP
        // ============================================================

        // Store username (e.g., "bartu") not email
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

        client.configMaps().inNamespace(namespace).resource(cm).createOrReplace()
        return name
    }
}