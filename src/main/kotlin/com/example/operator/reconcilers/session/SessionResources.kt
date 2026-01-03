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
        val baseName = "oauth2-proxy-config"
        val base = client.configMaps().inNamespace(namespace).withName(baseName).get()
            ?: throw IllegalStateException("Missing ConfigMap '$baseName' in namespace '$namespace'")

        val baseCfg = base.data?.get("oauth2-proxy.cfg")
            ?: throw IllegalStateException("ConfigMap '$baseName' missing key 'oauth2-proxy.cfg'")

        // Calculate dynamic values
        val issuerUrl = "${config.keycloakUrl}realms/${config.keycloakRealm}"
        val host = config.instancesHost ?: "theia.localtest.me"
        val scheme = config.ingressScheme.ifBlank { "http" }

        val hostWithPort = "$host:8080"
        val upstream = "http://127.0.0.1:$port/"

        // Replace placeholders in order
        var rendered = baseCfg
            .replace("ISSUER_URL_PLACEHOLDER", issuerUrl)
            .replace("SESSION_UID_PLACEHOLDER", sessionUid)
            .replace("http://127.0.0.1:placeholder-port/", upstream)

        // Replace placeholder-host in redirect_url BEFORE replacing in cookie_domains
        rendered = rendered.replace(
            "redirect_url=\"http://placeholder-host/",
            "redirect_url=\"${scheme}://${hostWithPort}/"
        )

        // Now replace remaining placeholder-host (in cookie_domains)
        rendered = rendered.replace("placeholder-host", host)

        log.info("Rendered oauth2-proxy.cfg for session {}:\n{}", sessionUid, rendered)

        val name = SessionNaming.sessionProxyCmName(user, appDefName, sessionUid)

        val cm = ConfigMapBuilder()
            .withNewMetadata()
            .withName(name)
            .withNamespace(namespace)
            .addToLabels("app.kubernetes.io/component", "session")
            .addToLabels("theia-cloud.io/session-uid", sessionUid)
            .addToLabels("theia-cloud.io/template-purpose", "proxy")
            .withOwnerReferences(OwnerRefs.controllerOwnerRef(owner))
            .endMetadata()
            .addToData("oauth2-proxy.cfg", rendered)
            .build()

        client.configMaps().inNamespace(namespace).resource(cm).createOrReplace()
        return name
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

        // Store username (e.g., "bartu") not email
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
            .withOwnerReferences(OwnerRefs.controllerOwnerRef(owner))
            .endMetadata()
            .addToData("authenticated-emails-list", user)
            .build()

        client.configMaps().inNamespace(namespace).resource(cm).createOrReplace()
        return name
    }
}