// File: AppDefinitionReconciler.kt
package com.example.hello

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.javaoperatorsdk.operator.api.reconciler.*
import org.slf4j.LoggerFactory
import ownerRef

@ControllerConfiguration(name = "appdefinition-controller")
class AppDefinitionReconciler(
    private val client: KubernetesClient
) : Reconciler<AppDefinition> {

    private val log = LoggerFactory.getLogger(AppDefinitionReconciler::class.java)

    override fun reconcile(resource: AppDefinition, context: Context<AppDefinition>): UpdateControl<AppDefinition> {
        val ns = resource.metadata?.namespace ?: "default"
        val name = resource.metadata?.name ?: "<no-name>"

        // ensure status exists
        if (resource.status == null) resource.status = AppDefinitionStatus()
        val status = resource.status!!

        // ensure spec exists
        val spec = resource.spec
        if (spec == null) {
            status.operatorStatus = "Error"
            status.operatorMessage = "spec is null"
            return UpdateControl.patchStatus(resource)
        }

        val ingressName = spec.ingressname

        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val existing = ingressClient.withName(ingressName).get()

        if (existing == null) {
            // Henkan mode: ingress is created by Helm / manually, not by operator
            log.info("Ingress '{}' not found for AppDefinition {}/{}. Create it manually (Henkan-like).", ingressName, ns, name)
        } else {
            val owners = (existing.metadata.ownerReferences ?: emptyList()).toMutableList()

            // remove any old controller=true ownerRef that points to THIS AppDefinition (cleanup)
            val cleaned = owners.filterNot { ref ->
                ref.controller == true && ref.uid == resource.metadata?.uid
            }.toMutableList()

            val hasNonControllerRef = cleaned.any { ref ->
                ref.uid == resource.metadata?.uid && ref.kind == resource.kind
            }

            if (!hasNonControllerRef) {
                cleaned.add(ownerRef(resource)) // ✅ Henkan-like ownerRef (non-controller)
                val patched = IngressBuilder(existing)
                    .editMetadata()
                    .withOwnerReferences(cleaned)
                    .endMetadata()
                    .build()

                ingressClient.resource(patched).createOrReplace()
                log.info("Added non-controller ownerRef AppDefinition {}/{} -> Ingress '{}'", ns, name, ingressName)
            }
        }

        status.operatorStatus = "HANDLED"
        status.operatorMessage = "AppDefinition handled"
        return UpdateControl.patchStatus(resource)
    }
}
