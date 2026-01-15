// File: WorkspaceReconciler.kt
package com.globalmaksimum.operator.reconcilers.workspace

import com.globalmaksimum.operator.AppDefinition
import com.globalmaksimum.operator.VolumeStatus
import com.globalmaksimum.operator.Workspace
import com.globalmaksimum.operator.WorkspaceStatus
import com.globalmaksimum.operator.config.OperatorConfig
import com.globalmaksimum.operator.naming.Labeling
import com.globalmaksimum.operator.naming.WorkspaceNaming
import com.globalmaksimum.operator.utils.OwnerRefs
import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Reconciler for Workspace custom resources.
 *
 * Responsibilities:
 * 1. Validate workspace spec (name, user, appDefinition are required)
 * 2. Add Henkan labels for UI integration
 * 3. Link workspace to AppDefinition via owner reference (enables cascading deletion)
 * 4. Create and manage PersistentVolumeClaim (PVC) for workspace storage
 * 5. Update workspace status throughout the process
 *
 * Status Lifecycle:
 * NEW → HANDLING → HANDLED (success)
 *   ↓                ↓
 *   └──────→ ERROR (failure, retries allowed)
 *
 * PVC Status Lifecycle:
 * started → Pending → Bound (Exists) → finished
 *              ↓
 *          (reschedule every 2s until Bound)
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

        // ============================================================
        // SECTION 1: INITIALIZATION & LOGGING
        // ============================================================

        // Extract basic metadata for logging and identification
        val name = resource.metadata?.name ?: "<no-name>"
        val ns = resource.metadata?.namespace ?: "<no-namespace>"
        log.info("Reconciling Workspace {}/{}", ns, name)

        // Ensure status exists so we can update it safely
        if (resource.status == null) resource.status = WorkspaceStatus()
        val status = resource.status!!

        // ============================================================
        // SECTION 2: STATUS-BASED SHORT-CIRCUITING
        // ============================================================

        // Check if workspace is already handled or in error state
        val opStatus = (status.operatorStatus ?: "NEW").uppercase()
        when (opStatus) {
            "HANDLED" -> return UpdateControl.noUpdate() // Already successfully processed, nothing to do
            "HANDLING", "NEW" -> {
                // Continue reconciliation normally
            }
            "ERROR" -> {
                // Allow retry for self-healing (continue processing)
            }
        }

        // ============================================================
        // SECTION 3: SPEC VALIDATION
        // ============================================================

        // Validate that spec exists
        val spec = resource.spec
        if (spec == null) {
            status.operatorStatus = "ERROR"
            status.operatorMessage = "spec is null"
            status.error = "Workspace spec is missing"
            return UpdateControl.patchStatus(resource)
        }

        // Validate required CRD fields
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

        // ============================================================
        // SECTION 4: METADATA & SPEC CHANGE TRACKING
        // ============================================================

        // Track whether we need to patch metadata/spec or just status
        var metadataChanged = false
        var specChanged = false

        // ============================================================
        // SECTION 5: HENKAN LABELS MANAGEMENT
        // ============================================================

        val meta = resource.metadata!!
        val labels = (meta.labels ?: emptyMap()).toMutableMap()

        // Helper function to add label only if it doesn't already exist
        fun putIfMissing(k: String, v: String?) {
            if (!v.isNullOrBlank() && !labels.containsKey(k)) {
                labels[k] = v
                metadataChanged = true
            }
        }

        // Read existing labels (Henkan-server may have already set these)
        val uiWorkspaceNameRaw = labels["app.henkan.io/workspaceName"]   // UI name (demo-ws)
        val projectNameRaw     = labels["app.henkan.io/henkanProjectName"] ?: spec.label
        val userRaw            = labels["app.henkan.io/workspaceUser"] ?: spec.user

        // If no UI name exists, derive one from spec.name and spec.user
        val fallbackUiName = WorkspaceNaming.deriveWorkspaceShortName(spec.name!!, spec.user!!)

        // Add labels if missing (putIfMissing sets metadataChanged = true if label is added)
        putIfMissing(
            "app.henkan.io/workspaceName",
            Labeling.toLabelValue(uiWorkspaceNameRaw) ?: Labeling.toLabelValue(fallbackUiName)
        )
        putIfMissing("app.henkan.io/workspaceUser", Labeling.toLabelValue(userRaw))
        putIfMissing("app.henkan.io/henkanProjectName", Labeling.toLabelValue(projectNameRaw))

        // Apply labels to metadata if any were added
        if (metadataChanged) meta.labels = labels

        log.info(
            "Workspace {}/{} Henkan labels: {}",
            ns, name, labels.filterKeys { it.startsWith("app.henkan.io/") }
        )


        // ============================================================
        // SECTION 6: LINK TO APPDEFINITION (OWNER REFERENCE)
        // ============================================================

        val appDefName = spec.appDefinition

        // Load the AppDefinition from the same namespace
        val appDef = client.resources(AppDefinition::class.java)
            .inNamespace(ns)
            .withName(appDefName)
            .get()

        if (appDef != null) {

            // AppDefinition exists, set owner reference
            val wsMeta = resource.metadata!!
            val refs = (wsMeta.ownerReferences ?: emptyList()).toMutableList()

            // Find the current controller owner (if any)
            val currentController = refs.firstOrNull { it.controller == true }
            val appUid = appDef.metadata.uid

            if (currentController?.uid != appUid) {
                // Remove old controller references (cleanup stale refs)
                val newRefs = refs.filterNot { it.controller == true }.toMutableList()

                // Add new controller reference pointing to the AppDefinition
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

        // ============================================================
        // SECTION 7: BEGIN PVC RECONCILIATION
        // ============================================================

        // Mark workspace as being processed (HANDLING state)
        status.operatorStatus = "HANDLING"

        // Initialize volume status fields
        status.volumeClaim = VolumeStatus(status = "started", message = "")
        status.volumeAttach = VolumeStatus(status = "started", message = "")

        // Call ensurePvc to create/update the PVC
        val pvcResult = resources.ensurePvc(resource)
        if (pvcResult.storageUpdated) specChanged = true

        // ============================================================
        // SECTION 8: HANDLE PVC STATUS
        // ============================================================

        // Map internal PVC result to Henkan-style status fields
        val volumeStatus = pvcResult.volumeStatus

        // Case 1: PVC is still being created or is being deleted (terminating)
        // Action: Update status and reschedule reconciliation in 2 seconds
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

        // Case 2: PVC is successfully bound (ready to use)
        // Action: Mark workspace as HANDLED (success)
        if (volumeStatus.status == "Exists") {
            status.volumeClaim = VolumeStatus("finished", "")
            status.volumeAttach = VolumeStatus("finished", "")
            status.operatorStatus = "HANDLED"
            status.operatorMessage = "Workspace handled"
        }

        // Case 3: PVC creation/binding failed
        // Action: Mark workspace as ERROR
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

        // ============================================================
        // SECTION 9: FINAL UPDATE CONTROL
        // ============================================================

        // One write to the primary resource:
        // - if spec or metadata changed -> patch resource + status together
        // - else -> patch only status
        return if (metadataChanged || specChanged) {
            UpdateControl.patchResourceAndStatus(resource)
        } else {
            UpdateControl.patchStatus(resource)
        }
    }

}