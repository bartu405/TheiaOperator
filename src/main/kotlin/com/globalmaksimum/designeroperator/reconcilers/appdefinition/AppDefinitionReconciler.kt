package com.globalmaksimum.designeroperator.reconcilers.appdefinition

import com.globalmaksimum.designeroperator.AppDefinition
import com.globalmaksimum.designeroperator.AppDefinitionStatus
import com.globalmaksimum.designeroperator.utils.OwnerRefs
import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import org.slf4j.LoggerFactory

/**
 * Reconciler for AppDefinition custom resources.
 *
 * Responsibilities:
 * 1. Validate AppDefinition spec
 * 2. Locate the shared Ingress resource (created by Helm, not by this operator)
 * 3. Add non-controller owner reference to the Ingress
 * 4. Mark AppDefinition as HANDLED once Ingress is found
 *
 * Status Lifecycle:
 * - NEW → HANDLING (waiting for Ingress) → HANDLED (success)
 * - NEW → ERROR (validation failure)
 */

@ControllerConfiguration(name = "appdefinition-controller")
class AppDefinitionReconciler(
    private val client: KubernetesClient
) : Reconciler<AppDefinition> {

    private val log = LoggerFactory.getLogger(AppDefinitionReconciler::class.java)

    override fun reconcile(resource: AppDefinition, context: Context<AppDefinition>): UpdateControl<AppDefinition> {

        val ns = resource.metadata?.namespace ?: "default"
        val name = resource.metadata?.name ?: "<no-name>"

        log.info("Reconciling AppDefinition {}/{}", ns, name)

        // ============================================================
        // SECTION 1: ENSURE STATUS EXISTS
        // ============================================================

        if (resource.status == null) resource.status = AppDefinitionStatus()
        val status = resource.status!!

        // ============================================================
        // SECTION 2: SPEC VALIDATION
        // ============================================================

        val spec = resource.spec
        if (spec == null) {
            status.operatorStatus = "ERROR"
            status.operatorMessage = "spec is null"
            return UpdateControl.patchStatus(resource)
        }

        // Validate required fields
        if (spec.name.isNullOrBlank() ||
            spec.image.isNullOrBlank() ||
            spec.port == null ||
            spec.uid == null ||
            spec.ingressname.isNullOrBlank()) {
            status.operatorStatus = "ERROR"
            status.operatorMessage = "Missing required fields"
            return UpdateControl.patchStatus(resource)
        }


        // ============================================================
        // SECTION 3: LOCATE AND ADD OWNER REFERENCE TO SHARED INGRESS
        // ============================================================

        val ingressName = spec.ingressname

        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val existing = ingressClient.withName(ingressName).get()

        if (existing == null) {
            // Henkan mode: ingress is created by Helm / manually, not by operator
            log.warn("Ingress '{}' not found for AppDefinition {}/{}. Waiting for Helm to create it.",
                ingressName, ns, name)
            status.operatorStatus = "HANDLING"
            status.operatorMessage = "Waiting for Ingress '$ingressName' to be created"
            return UpdateControl.patchStatus(resource)
        }
        else {
            // IMPORTANT (Henkan/Theia-Cloud style):
            // - treat the Ingress as Helm-managed / shared
            // - do NOT "clean up" or normalize ownerReferences
            // - append only if missing
            val owners = (existing.metadata.ownerReferences ?: emptyList()).toMutableList()

            val alreadyPresent = owners.any { ref ->
                ref.uid == resource.metadata?.uid && ref.kind == resource.kind
            }

            if (!alreadyPresent) {
                owners.add(OwnerRefs.ownerRef(resource)) // non-controller, informational
                existing.metadata.ownerReferences = owners

                // Patch only metadata; don't recreate/replace spec unintentionally
                ingressClient.resource(existing).patch()

                log.info(
                    "Added non-controller ownerRef AppDefinition {}/{} -> Ingress '{}'",
                    ns, name, ingressName
                )
            }
        }

        // ============================================================
        // SECTION 4: MARK AS HANDLED
        // ============================================================

        status.operatorStatus = "HANDLED"
        status.operatorMessage = "AppDefinition handled"
        return UpdateControl.patchStatus(resource)
    }
}