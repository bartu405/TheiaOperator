// File: Main.kt
package com.example.hello

import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.javaoperatorsdk.operator.Operator

fun main() {
    val client = KubernetesClientBuilder().build()

    val operator = Operator { overrider ->
        overrider
            .withKubernetesClient(client)
            .withUseSSAToPatchPrimaryResource(false)
    }

    operator.register(AppDefinitionReconciler(client))
    operator.register(WorkspaceReconciler(client))
    operator.register(SessionReconciler(client))


    operator.start()
    Thread.currentThread().join()
}
