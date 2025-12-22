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

        val opStatus = (status.operatorStatus ?: "NEW").uppercase()
        when (opStatus) {
            "HANDLED" -> return UpdateControl.noUpdate()
            "HANDLING" -> {
                status.operatorStatus = "ERROR"
                status.operatorMessage = "Handling was unexpectedly interrupted before."
                status.error = "Handling interrupted"
                return UpdateControl.patchStatus(resource)
            }
            "ERROR" -> return UpdateControl.noUpdate()
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

        val fallbackUiName = deriveWorkspaceShortName(spec.name!!, spec.user!!)
        putIfMissing(
            "app.henkan.io/workspaceName",
            toHenkanLabelValue(uiWorkspaceNameRaw) ?: toHenkanLabelValue(fallbackUiName)
        )
        putIfMissing("app.henkan.io/workspaceUser", toHenkanLabelValue(userRaw))
        putIfMissing("app.henkan.io/henkanProjectName", toHenkanLabelValue(projectNameRaw))

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
                newRefs.add(controllerOwnerRef(appDef))
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

        val pvcResult = ensurePvc(resource)
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

            return UpdateControl.patchStatus(resource)
                .rescheduleAfter(Duration.ofSeconds(2))
        }

        if (volumeStatus.status == "Exists") {
            status.volumeClaim = VolumeStatus(status = "finished", message = "")
            status.volumeAttach = VolumeStatus(status = "finished", message = "") // Theia-ish
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

    private data class EnsurePvcResult(
        val volumeStatus: VolumeStatus,
        val storageUpdated: Boolean
    )

    /**
     * Ensure a PVC exists for this workspace.
     * If spec.storage is missing, generate a name and set it on the in-memory Workspace object.
     *
     * IMPORTANT: does NOT call client.edit() on Workspace to avoid 409 conflicts.
     */
    private fun ensurePvc(ws: Workspace): EnsurePvcResult {
        val wsMeta = ws.metadata ?: return EnsurePvcResult(
            volumeStatus = VolumeStatus(status = "ERROR", message = "Workspace metadata is missing"),
            storageUpdated = false
        )

        val wsName = wsMeta.name ?: return EnsurePvcResult(
            volumeStatus = VolumeStatus(status = "ERROR", message = "Workspace metadata.name is missing"),
            storageUpdated = false
        )

        val ns = wsMeta.namespace ?: "default"

        val pvcName = try {
            TheiaNaming.workspaceStorageName(ws)
        } catch (e: Exception) {
            return EnsurePvcResult(
                volumeStatus = VolumeStatus(status = "ERROR", message = "Failed to compute storage name: ${e.message}"),
                storageUpdated = false
            )
        }

        val storageUpdated = (ws.spec?.storage != pvcName)
        ws.spec!!.storage = pvcName


        log.info("Using PVC name '{}' for workspace {}", pvcName, wsName)

        val pvcClient = client.persistentVolumeClaims().inNamespace(ns)
        val existing = pvcClient.withName(pvcName).get()

        // ✅ If it's terminating, do NOT report success. Keep reconciling until it's gone.
        if (existing != null && existing.metadata?.deletionTimestamp != null) {
            log.warn("PVC {}/{} is terminating; waiting before recreating", ns, pvcName)
            return EnsurePvcResult(
                volumeStatus = VolumeStatus(
                    status = "Deleting",
                    message = "PVC '$pvcName' is terminating"
                ),
                storageUpdated = storageUpdated
            )
        }

        // If PVC exists but is not Bound yet, keep reconciling (don’t mark HANDLED)
        if (existing != null) {
            val phase = existing.status?.phase // "Pending", "Bound", "Lost", etc.
            if (!phase.equals("Bound", ignoreCase = true)) {
                log.info("PVC {}/{} exists but not Bound yet (phase={}); waiting", ns, pvcName, phase)
                return EnsurePvcResult(
                    volumeStatus = VolumeStatus(
                        status = "Pending",
                        message = "PVC '$pvcName' exists but is not Bound (phase=${phase ?: "unknown"})"
                    ),
                    storageUpdated = storageUpdated
                )
            }
        }


        // For now, fixed default size.
        val size = "5Gi"
        val storageClassName: String? = null

        // --- Henkan-style label values derived from Workspace spec ---
        val spec = ws.spec!!
        val workspaceNameRaw = deriveWorkspaceShortName(spec.name!!, spec.user!!)
        val workspaceUserRaw = spec.user
        val projectNameRaw = spec.label

        val workspaceNameLabel = toHenkanLabelValue(workspaceNameRaw) ?: workspaceNameRaw
        val workspaceUserLabel = toHenkanLabelValue(workspaceUserRaw) ?: workspaceUserRaw
        val projectNameLabel = toHenkanLabelValue(projectNameRaw)

        if (existing != null) {
            // Update owner refs on PVC (safe to patch PVC separately)
            val wsUid = ws.metadata?.uid
            if (wsUid != null) {
                val currentRefs = existing.metadata.ownerReferences ?: emptyList()

                // Drop any old Workspace controller refs for this name
                val cleanedRefs = currentRefs.filterNot { ref ->
                    ref.controller == true &&
                            ref.kind == "Workspace" &&
                            ref.name == ws.metadata?.name
                }

                existing.metadata.ownerReferences = cleanedRefs + controllerOwnerRef(ws)
            }

            // Ensure Henkan labels on PVC
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

            return EnsurePvcResult(
                volumeStatus = VolumeStatus(
                    status = "Exists",
                    message = "PVC '$pvcName' exists and is Bound in namespace '$ns'"
                ),
                storageUpdated = storageUpdated
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

        log.info("PVC {} not found in namespace {}, creating it", pvcName, ns)

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
            pvcBuilder = pvcBuilder.withStorageClassName(storageClassName)
        }

        val pvc = pvcBuilder
            .endSpec()
            .build()

        pvc.metadata.ownerReferences = listOf(controllerOwnerRef(ws))

        pvcClient.resource(pvc).create()
        log.info("Created PVC {}/{} with Henkan labels", ns, pvcName)

        return EnsurePvcResult(
            volumeStatus = VolumeStatus(
                status = "Pending",
                message = "PVC '$pvcName' created; waiting for it to be Bound"
            ),
            storageUpdated = storageUpdated
        )

    }


    /**
     * Helper like Theia Cloud.
     */
    private fun Workspace.hasStorage(): Boolean {
        return this.spec?.storage?.isNotBlank() == true
    }

    private fun ensureStatus(resource: Workspace): WorkspaceStatus {
        if (resource.status == null) {
            resource.status = WorkspaceStatus()
        }
        return resource.status!!
    }

    private fun deriveWorkspaceShortName(wsIdOrName: String, user: String): String {
        val sanitizedUser = user
            .replace(Regex("[^a-zA-Z0-9-]"), "")
            .replace(Regex("-+"), "-")
            .trim('-')

        val prefix = "$sanitizedUser-"
        return if (wsIdOrName.startsWith(prefix) && wsIdOrName.length > prefix.length) {
            wsIdOrName.removePrefix(prefix)
        } else {
            wsIdOrName
        }
    }

    private fun toHenkanLabelValue(value: String?): String? {
        if (value.isNullOrBlank()) return null

        return value
            .replace(Regex("[^-_.a-zA-Z0-9]"), "-")
            .replace(Regex("^[^a-zA-Z0-9]+"), "")
            .replace(Regex("[^a-zA-Z0-9]+$"), "")
            .take(63)
    }
}
