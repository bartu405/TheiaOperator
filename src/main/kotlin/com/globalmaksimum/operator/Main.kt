// File: Main.kt
package com.globalmaksimum.operator

import com.globalmaksimum.operator.config.CliConfigParser
import com.globalmaksimum.operator.reconcilers.appdefinition.AppDefinitionReconciler
import com.globalmaksimum.operator.reconcilers.session.SessionReconciler
import com.globalmaksimum.operator.reconcilers.workspace.WorkspaceReconciler
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.javaoperatorsdk.operator.Operator

fun main(args: Array<String>) {
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

    SessionTimeoutReaper(client).start()

    operator.start()
    Thread.currentThread().join()
}
