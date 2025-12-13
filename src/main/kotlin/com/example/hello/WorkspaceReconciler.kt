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

        // Validate required CRD fields: spec.name, spec.user, spec.appDefinition, spec.storage

        if (spec.appDefinition.isNullOrBlank()) {
            status.operatorStatus = "Error"
            status.operatorMessage = "spec.appDefinition is required"
            status.error = "spec.appDefinition must be set"
            return UpdateControl.patchStatus(resource)
        }

        if (spec.storage.isNullOrBlank()) {
            status.operatorStatus = "Error"
            status.operatorMessage = "spec.storage is required"
            status.error = "spec.storage must be set"
            return UpdateControl.patchStatus(resource)
        }


        // --- Henkan-style labels on Workspace CR itself ---
        // We derive:
        //   app.henkan.io/henkanProjectName  <- slug(spec.label OR spec.options["henkanProjectName"])
        //   app.henkan.io/workspaceName      <- slug(spec.name)
        //   app.henkan.io/workspaceUser      <- slug(spec.user)
        val meta = resource.metadata!!
        val labels = (meta.labels ?: mutableMapOf()).toMutableMap()

        val existingWsNameLabel  = labels["app.henkan.io/workspaceName"]
        val existingUserLabel    = labels["app.henkan.io/workspaceUser"]
        val existingProjectLabel = labels["app.henkan.io/henkanProjectName"]

        // derive fallback values from spec
        val projectNameRaw = spec.label
        val workspaceNameRaw = spec.name!!
        val workspaceUserRaw = spec.user!!

        val projectNameLabel = toHenkanLabelValue(projectNameRaw)
        val workspaceNameLabel = toHenkanLabelValue(workspaceNameRaw)
        val workspaceUserLabel = toHenkanLabelValue(workspaceUserRaw)

        if (existingWsNameLabel == null && !workspaceNameLabel.isNullOrBlank()) {
            labels["app.henkan.io/workspaceName"] = workspaceNameLabel
        }
        if (existingUserLabel == null && !workspaceUserLabel.isNullOrBlank()) {
            labels["app.henkan.io/workspaceUser"] = workspaceUserLabel
        }
        if (existingProjectLabel == null && !projectNameLabel.isNullOrBlank()) {
            labels["app.henkan.io/henkanProjectName"] = projectNameLabel
        }

        meta.labels = labels
        client.resource(resource).inNamespace(ns).patch()
        log.info(
            "Workspace {}/{} labeled with Henkan labels: {}",
            ns, name, labels.filterKeys { it.startsWith("app.henkan.io/") }
        )


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

        // Map internal PVC result to Henkan-style status fields
        if (volumeStatus.status == "Created" || volumeStatus.status == "Exists") {
            // Successful PVC state -> Henkan-style "finished"
            status.volumeClaim = VolumeStatus(
                status = "finished",
                message = ""
            )
            status.volumeAttach = VolumeStatus(
                status = "finished",
                message = ""
            )
            status.error = null
            status.operatorStatus = "HANDLED"
            status.operatorMessage = "Workspace reconciled successfully"
        } else {
            // Error-ish PVC state, propagate details
            status.volumeClaim = volumeStatus
            status.volumeAttach = VolumeStatus(
                status = "Error",
                message = "PVC not ready: ${volumeStatus.message}"
            )
            status.operatorStatus = "Error"
            status.operatorMessage = "Failed to reconcile workspace PVC: ${volumeStatus.message}"
            status.error = volumeStatus.message
        }

        // 2) Return status patch
        return UpdateControl.patchStatus(resource)

    }

    /**
     * Ensure a PVC exists for this workspace.
     * Uses:
     *   - spec.name -> workspace name (for PVC name)
     *   - spec.storage -> PVC name (required)
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
        val spec = ws.spec
        val pvcName = spec?.storage ?: return VolumeStatus(
            status = "Error",
            message = "Workspace spec.storage (PVC name) is missing"
        )

        val pvcClient = client.persistentVolumeClaims().inNamespace(ns)
        val existing = pvcClient.withName(pvcName).get()

        // For now, we just use a fixed default size.
        // Later we can wire this from AppDefinition or elsewhere.
        val size = "5Gi"

        // No more options; storageClassName can be wired later if needed.
        val storageClassName: String? = null

        // --- Henkan-style label values derived from Workspace spec ---
        val workspaceNameRaw = spec?.name
        val workspaceUserRaw = spec?.user
        val projectNameRaw = spec?.label

        // sanitize to valid label values
        val workspaceNameLabel = toHenkanLabelValue(workspaceNameRaw) ?: workspaceNameRaw
        val workspaceUserLabel = toHenkanLabelValue(workspaceUserRaw) ?: workspaceUserRaw
        val projectNameLabel = toHenkanLabelValue(projectNameRaw)

        if (existing != null) {
            val wsUid = ws.metadata?.uid
            if (wsUid != null) {
                val currentRefs = existing.metadata.ownerReferences ?: emptyList()

                // 1) Drop any old Workspace controller refs (old UIDs etc.)
                val cleanedRefs = currentRefs.filterNot { ref ->
                    ref.controller == true &&
                            ref.kind == "Workspace" &&
                            ref.name == ws.metadata?.name
                }

                // 2) Add our current Workspace as *the* controller
                existing.metadata.ownerReferences = cleanedRefs + controllerOwnerRef(ws)
            }

            val pvcLabels = (existing.metadata.labels ?: emptyMap()).toMutableMap()

            if (!pvcLabels.containsKey("app.henkan.io/workspaceName")) {
                workspaceNameLabel?.let { pvcLabels["app.henkan.io/workspaceName"] = it }
            }
            if (!pvcLabels.containsKey("app.henkan.io/workspaceUser")) {
                workspaceUserLabel?.let { pvcLabels["app.henkan.io/workspaceUser"] = it }
            }
            if (!pvcLabels.containsKey("app.henkan.io/henkanProjectName")) {
                projectNameLabel?.let { pvcLabels["app.henkan.io/henkanProjectName"] = it }
            }


            existing.metadata.labels = pvcLabels
            pvcClient.resource(existing).patch()

            log.info(
                "PVC {} already exists in namespace {} with Henkan labels: {}",
                pvcName, ns, pvcLabels.filterKeys { it.startsWith("app.henkan.io/") }
            )

            return VolumeStatus(
                status = "Exists",
                message = "PVC '$pvcName' already exists in namespace '$ns'"
            )
        }


        // --- Build label map for a *new* PVC ---
        val labels = mutableMapOf(
            "app" to "theia-workspace",
            "workspace-name" to wsName,
            "theia-cloud.io/workspace-name" to wsName,
        )

        workspaceNameLabel?.let { labels["app.henkan.io/workspaceName"] = it }
        workspaceUserLabel?.let { labels["app.henkan.io/workspaceUser"] = it }
        projectNameLabel?.let { labels["app.henkan.io/henkanProjectName"] = it }

        // create fresh PVC with ownerRef
        log.info("PVC {} not found in namespace {}, creating it", pvcName, ns)

        // Note: we let the builder stay in the *SpecNested* type until endSpec()
        var pvcBuilder = PersistentVolumeClaimBuilder()
            .withNewMetadata()
            .withName(pvcName)
            .addToLabels(labels)
            .endMetadata()
            .withNewSpec()
            .withAccessModes("ReadWriteOnce")
            .withNewResources()
            .addToRequests("storage", Quantity(size))
            .endResources()

        if (!storageClassName.isNullOrBlank()) {
            pvcBuilder = pvcBuilder.withStorageClassName(storageClassName.toString())
        }

        val pvc = pvcBuilder
            .endSpec()
            .build()

        pvc.metadata.ownerReferences = listOf(controllerOwnerRef(ws))

        pvcClient.resource(pvc).create()
        log.info("Created PVC {}/{} with Henkan labels", ns, pvcName)

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
