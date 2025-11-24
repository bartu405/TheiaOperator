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
import org.slf4j.LoggerFactory


@ControllerConfiguration(
    name = "workspace-controller",
    finalizerName = WorkspaceReconciler.FINALIZER
)
class WorkspaceReconciler(
    private val client: KubernetesClient
) : Reconciler<Workspace>, Cleaner<Workspace> {

    companion object {
        const val FINALIZER = "workspaces.example.suleyman.io/finalizer"
    }

    private val log = LoggerFactory.getLogger(WorkspaceReconciler::class.java)

    override fun reconcile(
        resource: Workspace,
        context: Context<Workspace>
    ): UpdateControl<Workspace> {
        val name = resource.metadata?.name ?: "<no-name>"
        val ns = resource.metadata?.namespace ?: "<no-namespace>"


        log.info("Reconciling Workspace {}/{}", ns, name)

        // 1) Ensure PVC exists
        ensurePvc(resource)

        // 2) Only set ready=true if status.ready is null (first creation)
        if (resource.status == null) {
            resource.status = WorkspaceStatus(ready = true, message = "Workspace created")
        }



        return UpdateControl.patchStatus(resource)
    }

    override fun cleanup(resource: Workspace, context: Context<Workspace>): DeleteControl {
        val wsName = resource.metadata?.name ?: return DeleteControl.defaultDelete()
        val ns = resource.metadata?.namespace ?: "default"

        // Finds all Session CRs in the namespace, Delete any Sessions that reference this workspace
        val sessions = client.resources(Session::class.java)
            .inNamespace(ns)
            .list()
            .items
            .filter { it.spec?.workspaceName == wsName }

        sessions.forEach { session ->
            log.info("Workspace {} is deleted → removing Session {}", wsName, session.metadata.name)
            client.resource(session).delete()
        }

        // Now PVC cleanup logic
        val pvcName = "workspace-$wsName"
        val retain = resource.spec?.retainOnDelete ?: false

        if (retain) {
            log.info("Workspace {}/{} retainOnDelete=true → PVC NOT deleted", ns, wsName)
            return DeleteControl.defaultDelete()
        }

        log.info("Deleting PVC {}", pvcName)

        client.persistentVolumeClaims()
            .inNamespace(ns)
            .withName(pvcName)
            .delete()

        return DeleteControl.defaultDelete()
    }



    private fun ensurePvc(ws: Workspace) {
        val wsName = ws.metadata?.name ?: return
        val ns = ws.metadata?.namespace ?: "default"
        val pvcName = "workspace-$wsName"

        val pvcClient = client.persistentVolumeClaims().inNamespace(ns)

        // 1) Check if PVC already exists
        val existing = pvcClient.withName(pvcName).get()
        if (existing != null) {
            log.info("PVC {} already exists in namespace {}, skipping update", pvcName, ns)
            return
        }

        log.info("PVC {} not found in namespace {}, creating it", pvcName, ns)

        // 2) Build PVC spec
        val size = ws.spec?.storageSize ?: "5Gi"

        val pvc = PersistentVolumeClaimBuilder()
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
            .withStorageClassName(ws.spec?.storageClassName ?: "hostpath")
            .endSpec()
            .build()

        // 3) Create only (no createOrReplace)
        pvcClient.resource(pvc).create()
        log.info("Created PVC {}/{}", ns, pvcName)
    }
}