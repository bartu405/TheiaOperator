package com.globalmaksimum.operator.reconcilers.appdefinition

import com.globalmaksimum.operator.AppDefinition
import com.globalmaksimum.operator.AppDefinitionStatus
import com.globalmaksimum.operator.utils.OwnerRefs
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
 * 1. Validate AppDefinition spec (required fields: name, image, port, uid, ingressname)
 * 2. Locate the shared Ingress resource (created by Helm, not by this operator)
 * 3. Add non-controller owner reference to the Ingress (informational link)
 * 4. Mark AppDefinition as HANDLED once Ingress is found
 *
 * Key Design (Henkan/Theia-Cloud Style):
 * - Ingress is NOT created by this operator
 * - Ingress is created/managed by Helm charts or manually
 * - This reconciler only adds an owner reference to track the relationship
 * - Owner reference is NON-CONTROLLER (controller=false)
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

        // ============================================================
        // SECTION 1: INITIALIZATION & LOGGING
        // ============================================================

        val ns = resource.metadata?.namespace ?: "default"
        val name = resource.metadata?.name ?: "<no-name>"

        log.info("Reconciling AppDefinition {}/{}", ns, name)

        // ============================================================
        // SECTION 2: ENSURE STATUS EXISTS
        // ============================================================

        if (resource.status == null) resource.status = AppDefinitionStatus()
        val status = resource.status!!

        // ============================================================
        // SECTION 3: SPEC VALIDATION
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
        // SECTION 4: LOCATE SHARED INGRESS
        // ============================================================

        val ingressName = spec.ingressname

        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val existing = ingressClient.withName(ingressName).get()

        // ============================================================
        // SECTION 5: HANDLE MISSING INGRESS
        // ============================================================
        if (existing == null) {
            // Henkan mode: ingress is created by Helm / manually, not by operator
            log.warn("Ingress '{}' not found for AppDefinition {}/{}. Waiting for Helm to create it.",
                ingressName, ns, name)
            status.operatorStatus = "HANDLING"
            status.operatorMessage = "Waiting for Ingress '$ingressName' to be created"
            return UpdateControl.patchStatus(resource)
        }

        // ============================================================
        // SECTION 6: ADD OWNER REFERENCE TO INGRESS
        // ============================================================
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
        // SECTION 7: MARK AS HANDLED
        // ============================================================

        status.operatorStatus = "HANDLED"
        status.operatorMessage = "AppDefinition handled"
        return UpdateControl.patchStatus(resource)
    }
}