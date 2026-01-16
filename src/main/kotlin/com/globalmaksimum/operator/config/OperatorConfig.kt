// File: OperatorConfig.kt
package com.globalmaksimum.operator.config

data class OperatorConfig(
    val ingressHost: String = "theia.localtest.me",
    val ingressScheme: String = "https",

    val keycloakUrl: String? = null,
    val keycloakRealm: String? = null,
    val keycloakClientId: String? = null,

    val instancesHost: String? = null,
    val appId: String? = null,

    val oAuth2ProxyVersion: String? = null,

    val storageClassName: String? = null,
    val requestedStorage: String? = null,

    val sessionsPerUser: Int? = null,
    val serviceUrl: String? = null, // Henkan had https://<nil> looks like a bug
)