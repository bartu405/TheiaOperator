// File: SessionIngress.kt
package com.example.operator.reconcilers.session

import com.example.operator.AppDefinition
import com.example.operator.Session
import com.example.operator.config.OperatorConfig
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory

/**
 * Manages shared Ingress resources for Sessions.
 * Handles adding/removing session paths to/from a shared Ingress.
 */
class SessionIngress(
    private val client: KubernetesClient,
    private val config: OperatorConfig
) {
    private val log = LoggerFactory.getLogger(SessionIngress::class.java)

    private val ingressHost: String = (config.instancesHost ?: "theia.localtest.me").trim()

    /**
     * Determines the ingress name from the AppDefinition.
     */
    fun ingressNameForAppDef(appDef: AppDefinition): String =
        appDef.spec?.ingressname
            ?: "theia-${appDef.metadata?.name ?: "unknown-app"}-ingress"

    /**
     * Ensures the session has a path in the shared Ingress.
     */
    fun ensureSharedIngressForSession(
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
            ?: throw IllegalStateException("Shared Ingress '$ingressName' is missing")

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

        log.info(
            "Successfully ensured path {} on host {} in shared Ingress '{}'",
            path, host, ingressName
        )
    }

    /**
     * Removes the session's path from the shared Ingress during cleanup.
     */
    fun removeSessionFromIngress(session: Session) {
        val ns = session.metadata?.namespace ?: "default"
        val name = session.metadata?.name ?: "<no-name>"

        val appDefName = session.spec?.appDefinition
        if (appDefName.isNullOrBlank()) {
            log.warn(
                "Session {}/{} has no spec.appDefinition, cannot determine shared Ingress name, skipping ingress cleanup",
                ns, name
            )
            return
        }

        val appDef = client.resources(AppDefinition::class.java)
            .inNamespace(ns)
            .withName(appDefName)
            .get()

        if (appDef == null) {
            log.warn(
                "AppDefinition '{}' not found in namespace {}, cannot determine shared Ingress name, skipping ingress cleanup",
                appDefName, ns
            )
            return
        }

        val ingressName = ingressNameForAppDef(appDef)
        val host = sessionHost(session)
        val path = sessionPath(session)

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
            return
        }

        val rules = ingress.spec?.rules?.toMutableList() ?: mutableListOf()
        var changed = false

        val newRules = rules.map { rule ->
            if (rule.host != host) {
                rule
            } else {
                val http = rule.http
                val paths = http?.paths ?: emptyList()

                val filteredPaths = paths.filter { it.path != path }

                if (filteredPaths.size == paths.size) {
                    rule
                } else {
                    changed = true
                    if (filteredPaths.isEmpty()) {
                        // Keep host-only rule (no http section) as placeholder
                        IngressRuleBuilder(rule)
                            .withHost(host)
                            .withHttp(null)
                            .build()
                    } else {
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
            return
        }

        val updatedIngress = IngressBuilder(ingress)
            .editOrNewSpec()
            .withRules(newRules)
            .endSpec()
            .build()

        ingressClient.resource(updatedIngress).createOrReplace()

        log.info(
            "Successfully removed path from Ingress '{}'. Remaining rules: {}",
            ingressName, newRules.size
        )
    }

    /**
     * Gets the host for a session (currently uses the configured ingress host).
     */
    private fun sessionHost(@Suppress("UNUSED_PARAMETER") session: Session): String =
        ingressHost

    /**
     * Gets the path pattern for a session based on its UID.
     */
    private fun sessionPath(session: Session): String =
        "/${session.metadata?.uid}(/|$)(.*)"

    /**
     * Normalizes ingress rules by removing rules without HTTP paths.
     * Only keeps rules that have actual paths defined.
     */
    private fun normalizeIngressRules(rules: List<IngressRule>): MutableList<IngressRule> {
        val out = mutableListOf<IngressRule>()

        for (r in rules) {
            // Only keep rules that have actual paths
            if (r.http != null) {
                out.add(r)
            }
        }
        return out
    }
}