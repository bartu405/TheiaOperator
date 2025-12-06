// File: WorkspaceReconciler.kt
package com.example.hello

import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import controllerOwnerRef
import org.slf4j.LoggerFactory

@ControllerConfiguration(
    name = "workspace-controller"
)
class WorkspaceReconciler(
    private val client: KubernetesClient
) : Reconciler<Workspace> {

    private val log = LoggerFactory.getLogger(WorkspaceReconciler::class.java)

    override fun reconcile(
        resource: Workspace,
        context: Context<Workspace>
    ): UpdateControl<Workspace> {
        val name = resource.metadata?.name ?: "<no-name>"
        val ns = resource.metadata?.namespace ?: "<no-namespace>"
        log.info("Reconciling Workspace {}/{}", ns, name)

        // Ensure status exists so we can update it safely
        val status = ensureStatus(resource)

        val spec = resource.spec
        if (spec == null) {
            status.operatorStatus = "Error"
            status.operatorMessage = "spec is null"
            status.error = "Workspace spec is missing"
            return UpdateControl.patchStatus(resource)
        }

        // Validate required CRD fields: spec.name, spec.user
        if (spec.name.isNullOrBlank()) {
            status.operatorStatus = "Error"
            status.operatorMessage = "spec.name is required"
            status.error = "spec.name must be set"
            return UpdateControl.patchStatus(resource)
        }

        if (spec.user.isNullOrBlank()) {
            status.operatorStatus = "Error"
            status.operatorMessage = "spec.user is required"
            status.error = "spec.user must be set"
            return UpdateControl.patchStatus(resource)
        }

        // --- link Workspace -> AppDefinition (if specified) ---
        // CRD field is now spec.appDefinition (string)
        val appDefName = spec.appDefinition
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
                    log.info(
                        "Linked Workspace {}/{} as owned by AppDefinition {}/{}",
                        ns, name, ns, appDefName
                    )
                }
            } else {
                log.warn("AppDefinition {}/{} not found for Workspace {}/{}", ns, appDefName, ns, name)
                status.operatorStatus = "Warning"
                status.operatorMessage = "AppDefinition '$appDefName' not found in namespace '$ns'"
                // not fatal; workspace can still exist without an AppDefinition
            }
        }

        // 1) Ensure PVC exists (it will be owned by this Workspace)
        val volumeStatus = ensurePvc(resource)
        status.volumeClaim = volumeStatus

        // 2) Set high-level operator status
        status.error = null
        status.operatorStatus = "HANDLED"
        status.operatorMessage = "Workspace reconciled successfully"

        return UpdateControl.patchStatus(resource)
    }

    /**
     * Ensure a PVC exists for this workspace.
     * Uses:
     *   - spec.name -> workspace name (for PVC name)
     *   - spec.storage -> size (default to "5Gi" if null)
     *   - spec.options["storageClassName"] (optional) for storage class
     */
    private fun ensurePvc(ws: Workspace): VolumeStatus {
        val wsMeta = ws.metadata ?: return VolumeStatus(
            status = "Error",
            message = "Workspace metadata is missing"
        )

        val wsName = wsMeta.name ?: return VolumeStatus(
            status = "Error",
            message = "Workspace metadata.name is missing"
        )
        val ns = wsMeta.namespace ?: "default"
        val pvcName = "workspace-$wsName"

        val pvcClient = client.persistentVolumeClaims().inNamespace(ns)
        val existing = pvcClient.withName(pvcName).get()

        // Determine requested size (from spec.storage)
        val spec = ws.spec
        val size = spec?.storage ?: "5Gi" // sensible default
        // Optional: storageClassName from options["storageClassName"]
        val storageClassName = spec?.options?.get("storageClassName")

        if (existing != null) {
            // Ensure ownerReference back to Workspace
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

            return VolumeStatus(
                status = "Exists",
                message = "PVC '$pvcName' already exists in namespace '$ns'"
            )
        }

        // create fresh PVC with ownerRef
        log.info("PVC {} not found in namespace {}, creating it", pvcName, ns)

        var pvcBuilder = PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withName(pvcName)
            .addToLabels("app", "theia-workspace")
            .addToLabels("workspace-name", wsName)
            .addToLabels("theia-cloud.io/workspace-name", wsName)
            .endMetadata()
            .withNewSpec()
            .withAccessModes("ReadWriteOnce")
            .withNewResources()
            .addToRequests("storage", Quantity(size))
            .endResources()

        if (!storageClassName.isNullOrBlank()) {
            pvcBuilder = pvcBuilder.withStorageClassName(storageClassName)
        }

        val pvc = pvcBuilder
            .endSpec()
            .build()

        pvc.metadata.ownerReferences = listOf(controllerOwnerRef(ws))

        pvcClient.resource(pvc).create()
        log.info("Created PVC {}/{}", ns, pvcName)

        return VolumeStatus(
            status = "Created",
            message = "PVC '$pvcName' created in namespace '$ns' with size '$size'"
        )
    }

    private fun ensureStatus(resource: Workspace): WorkspaceStatus {
        if (resource.status == null) {
            resource.status = WorkspaceStatus()
        }
        return resource.status!!
    }
}
