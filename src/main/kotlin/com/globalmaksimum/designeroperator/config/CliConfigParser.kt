package com.globalmaksimum.designeroperator.config

object CliConfigParser {
    fun parse(args: Array<String>): OperatorConfig {

        // Get the VALUE of the flag, so we get the string after the input.
        // Args = ["--keycloakURL", "https://keycloak.com", "--appId", "henkan"]
        // When you do get("--keycloakURL") we get the value after the flag, which is https://keycloak.com
        fun get(flag: String): String? {
            val i = args.indexOf(flag)
            return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
        }

        val keycloakEnabled = args.contains("--keycloak")

        val cfg = OperatorConfig(
            keycloakUrl = get("--keycloakURL")?.trim(),
            keycloakRealm = get("--keycloakRealm")?.trim(),
            keycloakClientId = get("--keycloakClientId")?.trim(),
            appId = get("--appId")?.trim(),
            ingressScheme = (get("--ingressScheme") ?: "https").trim(),
            instancesHost = (get("--instancesHost") ?: "theia.localtest.me").trim(),
            oAuth2ProxyVersion = (get("--oAuth2ProxyVersion") ?: "quay.io/oauth2-proxy/oauth2-proxy:v7.5.1").trim(),
            storageClassName = get("--storageClassName")?.trim(),
            requestedStorage = get("--requestedStorage")?.trim(),
            sessionsPerUser = get("--sessionsPerUser")?.toIntOrNull(),
            serviceUrl = get("--serviceUrl")?.trim(),
        )

        // Check if the condition "keycloakXXX is not null or blank" holds, if not show error message.
        if (keycloakEnabled) {
            require(!cfg.keycloakUrl.isNullOrBlank()) { "Missing --keycloakURL" }
            require(!cfg.keycloakRealm.isNullOrBlank()) { "Missing --keycloakRealm" }
            require(!cfg.keycloakClientId.isNullOrBlank()) { "Missing --keycloakClientId" }
        }

        return cfg
    }
}