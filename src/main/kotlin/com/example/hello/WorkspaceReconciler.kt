package com.example.hello

import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.Cleaner
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import controllerOwnerRef
import org.slf4j.LoggerFactory


@ControllerConfiguration(
    name = "workspace-controller"
)
class WorkspaceReconciler(
    private val client: KubernetesClient
) : Reconciler<Workspace>{


    private val log = LoggerFactory.getLogger(WorkspaceReconciler::class.java)

    override fun reconcile(
        resource: Workspace,
        context: Context<Workspace>
    ): UpdateControl<Workspace> {
        val name = resource.metadata?.name ?: "<no-name>"
        val ns = resource.metadata?.namespace ?: "<no-namespace>"
        log.info("Reconciling Workspace {}/{}", ns, name)

        // --- link Workspace -> AppDefinition (if specified) ---
        val appDefName = resource.spec?.appDefinitionName
        if (!appDefName.isNullOrBlank()) {
            val appDef = client.resources(AppDefinition::class.java)
                .inNamespace(ns)
                .withName(appDefName)
                .get()

            if (appDef != null) {
                val wsMeta = resource.metadata
                val existingRefs = wsMeta.ownerReferences ?: emptyList()
                val alreadyOwned = existingRefs.any { it.uid == appDef.metadata.uid }

                if (!alreadyOwned) {
                    val newRefs = existingRefs.toMutableList()
                    newRefs.add(controllerOwnerRef(appDef))
                    wsMeta.ownerReferences = newRefs

                    client.resource(resource).inNamespace(ns).patch()
                }
            } else {
                log.warn("AppDefinition {}/{} not found for Workspace {}/{}", ns, appDefName, ns, name)
                // optional: set status.message etc.
            }
        }

        // 1) Ensure PVC exists (it will be owned by this Workspace)
        ensurePvc(resource)

        // 2) Only set ready=true on first creation
        if (resource.status == null) {
            resource.status = WorkspaceStatus().apply {
                ready = true
                message = "Workspace created"
            }
        }


        return UpdateControl.patchStatus(resource)
    }




    private fun ensurePvc(ws: Workspace) {
        val wsName = ws.metadata?.name ?: return
        val ns = ws.metadata?.namespace ?: "default"
        val pvcName = "workspace-$wsName"

        val pvcClient = client.persistentVolumeClaims().inNamespace(ns)
        val existing = pvcClient.withName(pvcName).get()

        if (existing != null) {
            val refs = existing.metadata.ownerReferences ?: emptyList()
            val wsUid = ws.metadata?.uid
            val hasWsOwner = refs.any { it.uid == wsUid }

            if (!hasWsOwner && wsUid != null) {
                existing.metadata.ownerReferences = refs + controllerOwnerRef(ws)
                pvcClient.resource(existing).patch()
                log.info(
                    "Patched PVC {} in ns {} to add ownerReference to Workspace {}",
                    pvcName, ns, wsName
                )
            } else {
                log.info(
                    "PVC {} already exists in namespace {} with correct ownerReferences, skipping",
                    pvcName, ns
                )
            }
            return
        }

        // create fresh PVC with ownerRef as before
        log.info("PVC {} not found in namespace {}, creating it", pvcName, ns)

        val size = ws.spec?.storageSize
        var pvcBuilder = PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withName(pvcName)
            .addToLabels("app", "theia-workspace")
            .addToLabels("workspace-name", wsName)
            .endMetadata()
            .withNewSpec()
            .withAccessModes("ReadWriteOnce")
            .withNewResources()
            .addToRequests("storage", Quantity(size))
            .endResources()

        if (ws.spec?.storageClassName != null) {
            pvcBuilder = pvcBuilder.withStorageClassName(ws.spec?.storageClassName)
        }

        val pvc = pvcBuilder.endSpec().build()
        pvc.metadata.ownerReferences = listOf(controllerOwnerRef(ws))

        pvcClient.resource(pvc).create()
        log.info("Created PVC {}/{}", ns, pvcName)
    }

}