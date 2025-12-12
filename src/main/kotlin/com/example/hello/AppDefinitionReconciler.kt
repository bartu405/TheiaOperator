// File: AppDefinitionReconciler.kt
package com.example.hello

import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import controllerOwnerRef

import org.slf4j.LoggerFactory

@ControllerConfiguration(name = "appdefinition-controller")
class AppDefinitionReconciler(
    private val client: KubernetesClient
) : Reconciler<AppDefinition> {

    private val log = LoggerFactory.getLogger(AppDefinitionReconciler::class.java)

    override fun reconcile(
        resource: AppDefinition,
        context: Context<AppDefinition>
    ): UpdateControl<AppDefinition> {
        val name = resource.metadata?.name ?: "<no-name>"
        val ns = resource.metadata?.namespace ?: "default"

        log.info("Reconciling AppDefinition {}/{}", ns, name)

        val spec = resource.spec
        val status = ensureStatus(resource)

        if (spec == null) {
            status.operatorStatus = "Error"
            status.operatorMessage = "spec is null"
            return UpdateControl.patchStatus(resource)
        }

        // Basic sanity checks matching the CRD’s required fields:
        // name, image, uid, port, ingressname, minInstances, maxInstances, requestsCpu,
        // requestsMemory, limitsMemory, limitsCpu
        val missing = mutableListOf<String>()

        if (spec.name.isNullOrBlank()) missing += "spec.name"
        if (spec.image.isNullOrBlank()) missing += "spec.image"
        if (spec.uid == null) missing += "spec.uid"
        if (spec.port == null) missing += "spec.port"
        if (spec.ingressname.isNullOrBlank()) missing += "spec.ingressname"
        if (spec.minInstances == null) missing += "spec.minInstances"
        if (spec.maxInstances == null) missing += "spec.maxInstances"
        if (spec.requestsCpu.isNullOrBlank()) missing += "spec.requestsCpu"
        if (spec.requestsMemory.isNullOrBlank()) missing += "spec.requestsMemory"
        if (spec.limitsCpu.isNullOrBlank()) missing += "spec.limitsCpu"
        if (spec.limitsMemory.isNullOrBlank()) missing += "spec.limitsMemory"

        if (missing.isNotEmpty()) {
            status.operatorStatus = "Error"
            status.operatorMessage =
                "Missing required fields: ${missing.joinToString(", ")}"
            log.warn(
                "AppDefinition {}/{} invalid: {}",
                ns, name, status.operatorMessage
            )
            return UpdateControl.patchStatus(resource)
        }

        // Optional: check minInstances <= maxInstances, like CRD validation rule
        if (spec.minInstances != null && spec.maxInstances != null &&
            spec.minInstances!! > spec.maxInstances!!
        ) {
            status.operatorStatus = "Error"
            status.operatorMessage =
                "minInstances (${spec.minInstances}) must be <= maxInstances (${spec.maxInstances})"
            log.warn("AppDefinition {}/{} invalid: {}", ns, name, status.operatorMessage)
            return UpdateControl.patchStatus(resource)
        }

        // If we reach here, spec looks good from operator POV
        status.operatorStatus = "HANDLED"
        status.operatorMessage = "AppDefinition is valid"

        // --- NEW: ensure AppDefinition is owner of existing shared Ingress (if any) ---
        val ingressName = spec.ingressname!!

        val ingressClient = client.network().v1().ingresses().inNamespace(ns)
        val existing = ingressClient.withName(ingressName).get()

        if (existing == null) {
            // Don't create an empty Ingress here; SessionReconciler will lazily create
            log.info(
                "Shared Ingress '{}' for AppDefinition {}/{} not found; it will be created by SessionReconciler when a Session is created",
                ingressName, ns, name
            )
        } else {
            val currentOwners = existing.metadata.ownerReferences ?: emptyList()
            val hasOwner = currentOwners.any { it.uid == resource.metadata?.uid }

            if (!hasOwner) {
                log.info(
                    "Adding AppDefinition {}/{} as owner of existing Ingress '{}'",
                    ns, name, ingressName
                )
                val newOwners = currentOwners.toMutableList()
                newOwners.add(controllerOwnerRef(resource))

                val patched = IngressBuilder(existing)
                    .editMetadata()
                    .withOwnerReferences(newOwners)
                    .endMetadata()
                    .build()

                ingressClient.resource(patched).createOrReplace()
            }
        }


        return UpdateControl.patchStatus(resource)
    }

    private fun ensureStatus(resource: AppDefinition): AppDefinitionStatus {
        if (resource.status == null) {
            resource.status = AppDefinitionStatus()
        }
        return resource.status!!
    }
}
