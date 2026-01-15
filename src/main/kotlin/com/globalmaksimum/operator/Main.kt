// File: Main.kt
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

    val config = CliConfigParser.parse(args)

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
