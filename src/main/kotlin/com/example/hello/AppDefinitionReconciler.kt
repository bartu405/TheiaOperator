package com.example.hello

import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
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
        status.operatorStatus = "Ready"
        status.operatorMessage = "AppDefinition is valid"

        return UpdateControl.patchStatus(resource)
    }

    private fun ensureStatus(resource: AppDefinition): AppDefinitionStatus {
        if (resource.status == null) {
            resource.status = AppDefinitionStatus()
        }
        return resource.status!!
    }
}
