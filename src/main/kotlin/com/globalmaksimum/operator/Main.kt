package com.globalmaksimum.operator

import com.globalmaksimum.operator.config.CliConfigParser
import com.globalmaksimum.operator.reconcilers.appdefinition.AppDefinitionReconciler
import com.globalmaksimum.operator.reconcilers.session.SessionReconciler
import com.globalmaksimum.operator.reconcilers.workspace.WorkspaceReconciler
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.javaoperatorsdk.operator.Operator
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {

    val log = LoggerFactory.getLogger("Main")

    // Get the OperatorConfig data class object with the arg values from CLI
    val config = CliConfigParser.parse(args)

    // Create a client to talk to kubernetes
    // The client automatically looks for your kubeconfig file at: ~/.kube/config
    // Development (your laptop) → Uses ~/.kube/config
    // Production (Henkan's Kubernetes) → Uses in-cluster service account
    val client = KubernetesClientBuilder().build()

    val operator = Operator { overrider ->
        overrider
            .withKubernetesClient(client)
            .withUseSSAToPatchPrimaryResource(false)
    }

    // Pass the OperatorConfig object to Workspace and Session Reconcilers, because they use the values
    operator.register(AppDefinitionReconciler(client))
    operator.register(WorkspaceReconciler(client, config))
    operator.register(SessionReconciler(client, config))

    val reaper = SessionTimeoutReaper(client)
    reaper.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutdown signal received, cleaning up...")
        try {
            reaper.stop()
            log.info("SessionTimeoutReaper stopped")

            operator.stop()
            log.info("Operator stopped")

            client.close()
            log.info("Kubernetes client closed")
        } catch (e: Exception) {
            log.error("Error during shutdown", e)
        }
    })

    log.info("Starting operator...")
    operator.start()

    log.info("Operator started successfully")
    Thread.currentThread().join()

}
