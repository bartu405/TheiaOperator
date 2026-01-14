// File: CliConfigParser.kt
package com.globalmaksimum.operator.config

object CliConfigParser {
    fun parse(args: Array<String>): OperatorConfig {
        fun get(flag: String): String? {
            val i = args.indexOf(flag)
            return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
        }
        fun has(flag: String): Boolean = args.contains(flag)

        val keycloakEnabled = has("--keycloak")

        val cfg = OperatorConfig(
            keycloakUrl = get("--keycloakURL") ?: System.getenv("THEIACLOUD_KEYCLOAK_URL"),
            keycloakRealm = get("--keycloakRealm") ?: System.getenv("THEIACLOUD_KEYCLOAK_REALM"),
            keycloakClientId = get("--keycloakClientId") ?: System.getenv("THEIACLOUD_KEYCLOAK_CLIENT_ID"),
            appId = get("--appId"),
            ingressScheme = (get("--ingressScheme") ?: System.getenv("INGRESS_SCHEME") ?: "https").trim(),
            instancesHost = (get("--instancesHost") ?: System.getenv("INSTANCES_HOST") ?: "theia.localtest.me").trim(),
            oAuth2ProxyImage = get("--oAuth2ProxyImage") ?: System.getenv("OAUTH2_PROXY_IMAGE") ?: "quay.io/oauth2-proxy/oauth2-proxy:v7.5.1",
            storageClassName = get("--storageClassName"),
            requestedStorage = get("--requestedStorage"),
            sessionsPerUser = get("--sessionsPerUser")?.toIntOrNull(),
            serviceUrl = get("--serviceUrl"),
        )

        if (keycloakEnabled) {
            require(!cfg.keycloakUrl.isNullOrBlank()) { "Missing --keycloakURL" }
            require(!cfg.keycloakRealm.isNullOrBlank()) { "Missing --keycloakRealm" }
            require(!cfg.keycloakClientId.isNullOrBlank()) { "Missing --keycloakClientId" }
        }

        return cfg
    }
}