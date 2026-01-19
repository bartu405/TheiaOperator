package com.globalmaksimum.designeroperator

import com.globalmaksimum.designeroperator.config.CliConfigParser
import com.globalmaksimum.designeroperator.reconcilers.appdefinition.AppDefinitionReconciler
import com.globalmaksimum.designeroperator.reconcilers.session.SessionReconciler
import com.globalmaksimum.designeroperator.reconcilers.workspace.WorkspaceReconciler
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.javaoperatorsdk.operator.Operator
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {

    val log = LoggerFactory.getLogger("Main")

    // Get the OperatorConfig data class object with the arg values from CLI
    val config = CliConfigParser.parse(args)

    // Create a client to talk to kubernetes
    val client = KubernetesClientBuilder().build()

    val operator = Operator { overrider ->
        overrider
            .withKubernetesClient(client)
            .withUseSSAToPatchPrimaryResource(false)
    }

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
