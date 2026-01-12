// File: WorkspaceResources.kt
package com.example.operator.reconcilers.workspace

import com.example.operator.VolumeStatus
import com.example.operator.Workspace
import com.example.operator.config.OperatorConfig
import com.example.operator.naming.Labeling
import com.example.operator.naming.WorkspaceNaming
import com.example.operator.utils.TemplateRenderer
import com.example.operator.utils.OwnerRefs
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory

class WorkspaceResources(
    private val client: KubernetesClient,
    private val config: OperatorConfig,
) {
    private val log = LoggerFactory.getLogger(WorkspaceResources::class.java)

    data class EnsurePvcResult(
        val volumeStatus: VolumeStatus,
        val storageUpdated: Boolean
    )

    /**
     * Ensure a PVC exists for this workspace.
     * If spec.storage is missing, generate a name and set it on the in-memory Workspace object.
     *
     * IMPORTANT: does NOT call client.edit() on Workspace to avoid 409 conflicts.
     */
    fun ensurePvc(ws: Workspace): EnsurePvcResult {
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
            WorkspaceNaming.workspaceStorageName(ws)
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

        // If it's terminating, do NOT report success. Keep reconciling until it's gone.
        if (existing != null && existing.metadata?.deletionTimestamp != null) {
            log.warn("PVC {}/{} is terminating; waiting before recreating", ns, pvcName)
            return EnsurePvcResult(
                volumeStatus = VolumeStatus(status = "Deleting", message = "PVC '$pvcName' is terminating"),
                storageUpdated = storageUpdated
            )
        }

        // If PVC exists but is not Bound yet, keep reconciling
        if (existing != null) {
            val phase = existing.status?.phase
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

        val size = config.requestedStorage?.takeIf { it.isNotBlank() } ?: "5Gi"
        val storageClassName = config.storageClassName?.takeIf { it.isNotBlank() }


        if (existing != null) {
            // Update owner refs on PVC
            val wsUid = ws.metadata?.uid
            if (wsUid != null) {
                val currentRefs = existing.metadata.ownerReferences ?: emptyList()

                val cleanedRefs = currentRefs.filterNot { ref ->
                    ref.controller == true &&
                            ref.kind == "Workspace" &&
                            ref.name == ws.metadata?.name
                }

                existing.metadata.ownerReferences = cleanedRefs + OwnerRefs.controllerOwnerRef(ws)
            }

            val pvcLabels = (existing.metadata.labels ?: emptyMap()).toMutableMap()

            if (!pvcLabels.containsKey("theia-cloud.io/workspace-name")) {
                pvcLabels["theia-cloud.io/workspace-name"] = wsMeta.name!!
                existing.metadata.labels = pvcLabels
                pvcClient.resource(existing).patch()
            }


            existing.metadata.labels = pvcLabels
            pvcClient.resource(existing).patch()

            log.info(
                "PVC {} already exists in namespace {} with Henkan labels: {}",
                pvcName, ns, pvcLabels.filterKeys { it.startsWith("app.henkan.io/") }
            )

            return EnsurePvcResult(
                volumeStatus = VolumeStatus(status = "Exists", message = "PVC '$pvcName' exists and is Bound in namespace '$ns'"),
                storageUpdated = storageUpdated
            )
        }

        val labels = mutableMapOf(
            "theia-cloud.io/workspace-name" to wsName
        )

        log.info("PVC {} not found in namespace {}, creating it", pvcName, ns)

        val rendered = renderPvcYaml(
            ns = ns,
            pvcName = pvcName,
            size = size,
            storageClassName = storageClassName,
            labels = labels
        )

        val pvc = loadPvcFromYaml(rendered)

        pvc.metadata.ownerReferences = listOf(OwnerRefs.controllerOwnerRef(ws))
        pvc.metadata.namespace = ns

        pvcClient.resource(pvc).create()
        log.info("Created PVC {}/{} from Velocity template", ns, pvcName)

        return EnsurePvcResult(
            volumeStatus = VolumeStatus(status = "Pending", message = "PVC '$pvcName' created; waiting for it to be Bound"),
            storageUpdated = storageUpdated
        )
    }

    private fun renderPvcYaml(
        ns: String,
        pvcName: String,
        size: String,
        storageClassName: String?,
        labels: Map<String, String>
    ): String {
        val model = mapOf(
            "namespace" to ns,
            "pvcName" to pvcName,
            "size" to size,
            "storageClassName" to storageClassName,
            "labels" to labels
        )

        return TemplateRenderer.render(
            templatePath = "templates/theia-pvc.yaml.vm",
            model = model
        )
    }

    private fun loadPvcFromYaml(yaml: String): PersistentVolumeClaim {
        return client.persistentVolumeClaims()
            .load(yaml.byteInputStream())
            .item()
            ?: error("Velocity rendered PVC YAML but Fabric8 returned null item")
    }
}
