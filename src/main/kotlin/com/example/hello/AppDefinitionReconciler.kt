package com.example.hello

import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.*
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
        val status = resource.status ?: AppDefinitionStatus().also {
            resource.status = it
        }

        val image = resource.spec?.image
        if (image.isNullOrBlank()) {
            status.ready = false
            status.message = "spec.image must be set"
        } else {
            status.ready = true
            status.message = "AppDefinition is valid"
        }

        return UpdateControl.patchStatus(resource)
    }

}