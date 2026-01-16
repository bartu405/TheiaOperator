// File: OperatorConfig.kt
package com.globalmaksimum.operator.config

data class OperatorConfig(
    val ingressHost: String = "theia.localtest.me",
    val ingressScheme: String = "https",

    val keycloakUrl: String? = null,
    val keycloakRealm: String? = null,
    val keycloakClientId: String? = null,

    val instancesHost: String? = null,      // e.g. designer.172.x...sslip.io
    val appId: String? = null,              // e.g. henkan

    val oAuth2ProxyVersion: String? = null,

    val storageClassName: String? = null,   // e.g. local-path
    val requestedStorage: String? = null,   // e.g. 250Mi

    val sessionsPerUser: Int? = null,       // ignore for now if you want
    val serviceUrl: String? = null,         // Henkan had https://<nil> - optional
)