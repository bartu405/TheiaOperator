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

@ControllerConfiguration(name = "appdefinition-controller")
class AppDefinitionReconciler(
    private val client: KubernetesClient
) : Reconciler<AppDefinition> {

    private val log = LoggerFactory.getLogger(AppDefinitionReconciler::class.java)

    override fun reconcile(resource: AppDefinition, context: Context<AppDefinition>): UpdateControl<AppDefinition> {
        val ns = resource.metadata?.namespace ?: "default"
        val name = resource.metadata?.name ?: "<no-name>"

        // Ensure status exists
        if (resource.status == null) resource.status = AppDefinitionStatus()
        val status = resource.status!!

        // Ensure spec exists
        val spec = resource.spec
        if (spec == null) {
            status.operatorStatus = "ERROR"
            status.operatorMessage = "spec is null"
            return UpdateControl.patchStatus(resource)
        }

        // Required fields validation
        val specName = spec.name
        val image = spec.image
        val port = spec.port
        val uid = spec.uid

        if (specName.isNullOrBlank() || image.isNullOrBlank() || port == null || uid == null) {
            status.operatorStatus = "ERROR"
            status.operatorMessage = "Missing required fields: name, image, port, or uid"
            return UpdateControl.patchStatus(resource)
        }

        val ingressName = spec.ingressname

        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val existing = ingressClient.withName(ingressName).get()

        if (existing == null) {
            // Henkan mode: ingress is created by Helm / manually, not by operator
            log.info("Ingress '{}' not found for AppDefinition {}/{}. Create it manually (Henkan-like).", ingressName, ns, name)
        } else {
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

        status.operatorStatus = "HANDLED"
        status.operatorMessage = "AppDefinition handled"
        return UpdateControl.patchStatus(resource)
    }
}