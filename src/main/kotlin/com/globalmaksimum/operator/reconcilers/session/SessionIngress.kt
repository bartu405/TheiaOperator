// File: SessionIngress.kt
package com.globalmaksimum.operator.reconcilers.session

import com.globalmaksimum.operator.AppDefinition
import com.globalmaksimum.operator.Session
import com.globalmaksimum.operator.config.OperatorConfig
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory

/**
 * Manages shared Ingress resources for Sessions.
 *
 * Responsibilities:
 * 1. Add session-specific paths to a shared Ingress (created by Helm/manually)
 * 2. Remove session paths from Ingress during cleanup
 * 3. Preserve existing Ingress rules (avoid overwriting Helm-managed configuration)
 *
 * Key Design (Henkan/Theia-Cloud Style):
 * - One shared Ingress per AppDefinition (e.g., "theia-ingress")
 * - Multiple Sessions add paths to the same Ingress
 * - Ingress is created/managed by Helm, this class only modifies paths
 * - Each Session gets a unique path based on its UID
 */
class SessionIngress(
    private val client: KubernetesClient,
    private val config: OperatorConfig
) {
    private val log = LoggerFactory.getLogger(SessionIngress::class.java)

    private val ingressHost: String = (config.instancesHost ?: "theia.localtest.me").trim()

    // Gets the ingress name from the AppDefinition.
    fun ingressNameForAppDef(appDef: AppDefinition): String =
        appDef.spec?.ingressname
            ?: "theia-${appDef.metadata?.name ?: "unknown-app"}-ingress"

    // Ensures the session has a path in the shared Ingress.
    fun ensureSharedIngressForSession(
        session: Session,
        serviceName: String,
        ingressName: String,
        appDef: AppDefinition,
        port: Int
    ) {
        val ns = session.metadata?.namespace ?: "default"
        val host = ingressHost
        val path = sessionPath(session)

        // ============================================================
        // SECTION 1: LOAD SHARED INGRESS
        // ============================================================

        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val existing = ingressClient.withName(ingressName).get()
            ?: throw IllegalStateException("Shared Ingress '$ingressName' is missing")

        // ============================================================
        // SECTION 2: FIND OR CREATE INGRESS RULE FOR HOST
        // ============================================================

        val ingress = IngressBuilder(existing).build()
        val rules = ingress.spec?.rules?.toMutableList() ?: mutableListOf()

        // Prefer a rule with http; do NOT hijack a host-only rule
        val ruleIndex = rules.indexOfFirst { it.host == host && it.http != null }

        // CASE 1: NO HTTP RULE FOR THIS HOST YET
        if (ruleIndex == -1) {
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
        }
        // CASE 2: HTTP RULE EXISTS FOR THIS HOST
        else {
            val rule = rules[ruleIndex]
            val paths = rule.http?.paths?.toMutableList() ?: mutableListOf()

            val existingPathIndex = paths.indexOfFirst { it.path == path }

            // PATH DOESN'T EXIST YET
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
            }
            // PATH ALREADY EXISTS
            else {
                val existingPath = paths[existingPathIndex]
                existingPath.backend?.service?.name = serviceName
                existingPath.backend?.service?.port?.number = port
                existingPath.backend?.service?.port?.name = null
            }

            // Rebuild the rule with updated paths
            rules[ruleIndex] = IngressRuleBuilder(rule)
                .editOrNewHttp()
                .withPaths(paths)
                .endHttp()
                .build()
        }

        // ============================================================
        // SECTION 3: UPDATE INGRESS
        // ============================================================

        ingress.spec?.rules = rules
        ingressClient.resource(ingress).createOrReplace()

        log.info(
            "Successfully ensured path {} on host {} in shared Ingress '{}'",
            path, host, ingressName
        )
    }

    // Removes the session's path from the shared Ingress during cleanup.
    fun removeSessionFromIngress(session: Session) {
        val ns = session.metadata?.namespace ?: "default"
        val name = session.metadata?.name ?: "<no-name>"

        // ============================================================
        // SECTION 1: DETERMINE INGRESS NAME
        // ============================================================

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
        val host = ingressHost
        val path = sessionPath(session)

        log.info(
            "Running cleanup for Session {}/{}: removing path {} on host {} from shared Ingress '{}'",
            ns, name, path, host, ingressName
        )

        // ============================================================
        // SECTION 2: LOAD SHARED INGRESS
        // ============================================================

        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val ingress = ingressClient.withName(ingressName).get()

        if (ingress == null) {
            log.info(
                "Shared Ingress '{}' not found in namespace {}, nothing to cleanup",
                ingressName, ns
            )
            return
        }

        // ============================================================
        // SECTION 3: REMOVE SESSION PATH FROM RULES
        // ============================================================

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

        // ============================================================
        // SECTION 4: UPDATE INGRESS
        // ============================================================

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

    // Gets the path pattern for a session based on its UID
    private fun sessionPath(session: Session): String =
        "/${session.metadata?.uid}(/|$)(.*)"

}