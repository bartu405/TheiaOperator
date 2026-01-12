// File: SessionResources.kt
package com.example.operator.reconcilers.session

import com.example.operator.Session
import com.example.operator.config.OperatorConfig
import com.example.operator.naming.SessionNaming
import com.example.operator.utils.TemplateRenderer
import com.example.operator.utils.OwnerRefs
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

/**
 * Manages the creation and updates of Kubernetes resources for Sessions.
 * Handles Deployments, Services, and ConfigMaps.
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
        val yaml = TemplateRenderer.render(
            "templates/theia-service.yaml.vm",
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

        val resources = client.load(ByteArrayInputStream(yaml.toByteArray())).items()
        resources.forEach { r ->
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
        // 1. Calculate the values needed for the template
        val issuerUrl = "${config.keycloakUrl}realms/${config.keycloakRealm}"
        val host = config.instancesHost ?: "theia.localtest.me"
        val scheme = config.ingressScheme.ifBlank { "http" }

        val fullHost = "${host}/${sessionUid}"
        val redirectUrl = "${scheme}://${fullHost}/oauth2/callback"
        val upstreamUrl = "http://127.0.0.1:$port/"

        val cmName = SessionNaming.sessionProxyCmName(user, appDefName, sessionUid)

        // 2. Check if ConfigMap already exists and get existing labels
        val existingCm = client.configMaps()
            .inNamespace(namespace)
            .withName(cmName)
            .get()

        val labels = existingCm?.metadata?.labels?.toMutableMap() ?: mutableMapOf()

        // 3. Add labels only if missing (don't override henkan-server's labels)
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

        // 4. Prepare the model for Velocity
        val model = mapOf(
            "configMapName" to cmName,
            "namespace" to namespace,
            "sessionUid" to sessionUid,
            "redirectUrl" to redirectUrl,
            "issuerUrl" to issuerUrl,
            "upstreamUrl" to upstreamUrl,
            "cookieDomain" to host,
        )

        // 5. Render the YAML from the template
        val yaml = TemplateRenderer.render(
            "templates/theia-oauth2-proxy-config.yaml.vm",
            model
        )

        // 6. Load and apply with merged labels
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
        val name = SessionNaming.sessionEmailCmName(user, appDefName, sessionUid)

        // Check if ConfigMap already exists and get existing labels
        val existingCm = client.configMaps()
            .inNamespace(namespace)
            .withName(name)
            .get()

        val labels = existingCm?.metadata?.labels?.toMutableMap() ?: mutableMapOf()

        // Add labels only if missing
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

        client.configMaps().inNamespace(namespace).resource(cm).createOrReplace()
        return name
    }
}