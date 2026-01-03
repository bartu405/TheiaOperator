// File: WorkspaceReconciler.kt
package com.example.operator.reconcilers.workspace

import com.example.operator.AppDefinition
import com.example.operator.VolumeStatus
import com.example.operator.Workspace
import com.example.operator.WorkspaceStatus
import com.example.operator.config.OperatorConfig
import com.example.operator.naming.Labeling
import com.example.operator.naming.WorkspaceNaming
import com.example.operator.utils.OwnerRefs
import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import org.slf4j.LoggerFactory
import java.time.Duration

/*
1. **Generates storage names** rather than requiring them upfront
2. **Updates the workspace spec** with the generated storage name
3. **Sets appropriate status** at each stage like Theia Cloud does
4. **Includes a `hasStorage()`** helper method like in Theia Cloud
5. **Maintains ownership relationships** between resources

Key Theia Cloud-like behaviors:
- The storage field isn't required upfront - it gets generated and set during reconciliation
- Progress is tracked with detailed status updates
- The storage name follows a predictable pattern based on the workspace name
- Owner references ensure proper resource lifecycle management
- The PVC is properly labeled for identification
- The code now handles both cases: workspaces that don't yet have storage defined, and existing workspaces that already have storage configured.
*/

@ControllerConfiguration(
    name = "workspace-controller"
)
class WorkspaceReconciler(
    private val client: KubernetesClient,
    private val config: OperatorConfig
) : Reconciler<Workspace> {

    private val log = LoggerFactory.getLogger(WorkspaceReconciler::class.java)

    private val resources = WorkspaceResources(client, config)

    override fun reconcile(
        resource: Workspace,
        context: Context<Workspace>
    ): UpdateControl<Workspace> {
        val name = resource.metadata?.name ?: "<no-name>"
        val ns = resource.metadata?.namespace ?: "<no-namespace>"
        log.info("Reconciling Workspace {}/{}", ns, name)



        // Ensure status exists so we can update it safely
        val status = ensureStatus(resource)

        val opStatus = (status.operatorStatus ?: "NEW").uppercase()
        when (opStatus) {
            "HANDLED" -> return UpdateControl.noUpdate()
            "HANDLING", "NEW" -> {
                // continue reconciliation normally
            }
            "ERROR" -> {
                // Optional: allow retry (recommended), or noUpdate if you want "manual intervention"
                // I'd recommend continuing so it can self-heal:
                // (do nothing here and continue)
            }
        }



        val spec = resource.spec
        if (spec == null) {
            status.operatorStatus = "ERROR"
            status.operatorMessage = "spec is null"
            status.error = "Workspace spec is missing"
            return UpdateControl.patchStatus(resource)
        }

        // Validate required CRD fields: spec.name, spec.user, spec.appDefinition
        if (spec.name.isNullOrBlank()) {
            status.operatorStatus = "ERROR"
            status.operatorMessage = "spec.name is required"
            status.error = "spec.name must be set"
            return UpdateControl.patchStatus(resource)
        }

        if (spec.user.isNullOrBlank()) {
            status.operatorStatus = "ERROR"
            status.operatorMessage = "spec.user is required"
            status.error = "spec.user must be set"
            return UpdateControl.patchStatus(resource)
        }

        if (spec.appDefinition.isNullOrBlank()) {
            status.operatorStatus = "ERROR"
            status.operatorMessage = "spec.appDefinition is required"
            status.error = "spec.appDefinition must be set"
            return UpdateControl.patchStatus(resource)
        }



        var metadataChanged = false
        var specChanged = false

        // --- Henkan-style labels on Workspace CR itself ---
        val meta = resource.metadata!!
        val labels = (meta.labels ?: emptyMap()).toMutableMap()

        fun putIfMissing(k: String, v: String?) {
            if (!v.isNullOrBlank() && !labels.containsKey(k)) {
                labels[k] = v
                metadataChanged = true
            }
        }

        // Prefer existing labels (Henkan-server sets them). Only fallback if missing.
        val uiWorkspaceNameRaw = labels["app.henkan.io/workspaceName"]   // UI name (demo-ws)
        val projectNameRaw     = labels["app.henkan.io/henkanProjectName"] ?: spec.label
        val userRaw            = labels["app.henkan.io/workspaceUser"] ?: spec.user

        val fallbackUiName = WorkspaceNaming.deriveWorkspaceShortName(spec.name!!, spec.user!!)
        putIfMissing(
            "app.henkan.io/workspaceName",
            Labeling.toLabelValue(uiWorkspaceNameRaw) ?: Labeling.toLabelValue(fallbackUiName)
        )
        putIfMissing("app.henkan.io/workspaceUser", Labeling.toLabelValue(userRaw))
        putIfMissing("app.henkan.io/henkanProjectName", Labeling.toLabelValue(projectNameRaw))

        if (metadataChanged) meta.labels = labels

        log.info(
            "Workspace {}/{} Henkan labels: {}",
            ns, name, labels.filterKeys { it.startsWith("app.henkan.io/") }
        )

        // --- link Workspace -> AppDefinition (if specified) ---
        val appDefName = spec.appDefinition
        val appDef = client.resources(AppDefinition::class.java)
            .inNamespace(ns)
            .withName(appDefName)
            .get()

        if (appDef != null) {
            val wsMeta = resource.metadata!!
            val refs = (wsMeta.ownerReferences ?: emptyList()).toMutableList()

            val currentController = refs.firstOrNull { it.controller == true }
            val appUid = appDef.metadata.uid

            if (currentController?.uid != appUid) {
                val newRefs = refs.filterNot { it.controller == true }.toMutableList()
                newRefs.add(OwnerRefs.controllerOwnerRef(appDef))
                wsMeta.ownerReferences = newRefs
                metadataChanged = true

                log.info("Linked Workspace {}/{} as owned by AppDefinition {}/{}", ns, name, ns, appDefName)
            }
        } else {
            log.warn("AppDefinition {}/{} not found for Workspace {}/{}", ns, appDefName, ns, name)
            status.operatorStatus = "ERROR"
            status.operatorMessage = "AppDefinition '$appDefName' not found in namespace '$ns'"
            status.error = status.operatorMessage
            return UpdateControl.patchStatus(resource)
        }

        // Set workspace status to being handled (like Theia Cloud)
        status.operatorStatus = "HANDLING"

        // Start PVC creation with status updates at various stages
        status.volumeClaim = VolumeStatus(status = "started", message = "")
        status.volumeAttach = VolumeStatus(status = "started", message = "")

        val pvcResult = resources.ensurePvc(resource)
        if (pvcResult.storageUpdated) specChanged = true

        // Map internal PVC result to Henkan-style status fields
        val volumeStatus = pvcResult.volumeStatus
        if (volumeStatus.status == "Deleting" || volumeStatus.status == "Pending") {
            status.volumeClaim = VolumeStatus(status = "started", message = volumeStatus.message)

            status.volumeAttach = when (volumeStatus.status) {
                "Deleting" -> VolumeStatus(status = "started", message = "waiting for PVC deletion")
                else       -> VolumeStatus(status = "started", message = "waiting for PVC to be Bound")
            }

            status.operatorStatus = "HANDLING"
            status.operatorMessage = volumeStatus.message
            status.error = null

            val ctl =
                if (metadataChanged || specChanged) UpdateControl.patchResourceAndStatus(resource)
                else UpdateControl.patchStatus(resource)

            return ctl.rescheduleAfter(Duration.ofSeconds(2))

        }

        if (volumeStatus.status == "Exists") {

            // FIRST time: emit "claimed"
            if (status.volumeAttach?.status != "claimed") {
                status.volumeClaim = VolumeStatus(status = "finished", message = "")
                status.volumeAttach = VolumeStatus(
                    status = "claimed",
                    message = "PVC is bound"
                )
                status.error = null
                status.operatorStatus = "HANDLING"
                status.operatorMessage = "PVC bound, finalizing workspace"

                return UpdateControl.patchStatus(resource)
                    .rescheduleAfter(Duration.ofSeconds(1))
            }

            // SECOND time: finalize to "finished"
            status.volumeAttach = VolumeStatus(status = "finished", message = "")
            status.error = null
            status.operatorStatus = "HANDLED"
            status.operatorMessage = "Workspace reconciled successfully"
        }
        else {
            status.volumeClaim = volumeStatus
            status.volumeAttach = VolumeStatus(
                status = "ERROR",
                message = "PVC not ready: ${volumeStatus.message}"
            )
            status.operatorStatus = "ERROR"
            status.operatorMessage = "Failed to reconcile workspace PVC: ${volumeStatus.message}"
            status.error = volumeStatus.message
        }



        // One write to the primary resource:
        // - if spec or metadata changed -> patch resource + status together
        // - else -> patch only status
        return if (metadataChanged || specChanged) {
            UpdateControl.patchResourceAndStatus(resource)
        } else {
            UpdateControl.patchStatus(resource)
        }
    }

    private fun ensureStatus(resource: Workspace): WorkspaceStatus {
        if (resource.status == null) {
            resource.status = WorkspaceStatus()
        }
        return resource.status!!
    }

}