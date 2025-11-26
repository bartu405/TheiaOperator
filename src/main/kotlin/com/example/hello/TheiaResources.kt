package com.example.hello

import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Version

// ===== AppDefinition =====

data class EnvVarSpec(
    val name: String = "",
    val value: String? = null
)


data class AppDefinitionSpec(
    val image: String,
    val port: Int = 3000,
    val uid: Int? = null,

    val requestsCpu: String? = null,
    val requestsMemory: String? = null,
    val limitsCpu: String? = null,
    val limitsMemory: String? = null,

    val env: List<EnvVarSpec>? = null,
    val mountPath: String? = "/home/project"
)




data class AppDefinitionStatus(
    var ready: Boolean = false,
    var message: String? = null
)

@Group("example.suleyman.io")
@Version("v1alpha1")
class AppDefinition :
    CustomResource<AppDefinitionSpec, AppDefinitionStatus>(),
    Namespaced


// ===== Workspace =====

data class WorkspaceSpec(
    val owner: String? = null,
    val appDefinitionName: String? = null,
    val label: String? = null, // user field in theia cloud
    val storageSize: String = "5Gi",
    val storageClassName: String?,

)

data class WorkspaceStatus(
    var ready: Boolean = false,
    var message: String? = null
)

@Group("example.suleyman.io")
@Version("v1alpha1")
class Workspace :
    CustomResource<WorkspaceSpec, WorkspaceStatus>(),
    Namespaced


// ===== Session =====

data class SessionSpec(
    val workspaceName: String? = null,
    val appDefinitionName: String? = null,
    val user: String? = null,
    val envVars: Map<String, String>? = null,
    val envVarsFromConfigMaps: List<String>? = null,
    val envVarsFromSecrets: List<String>? = null
)


data class SessionStatus(
    var ready: Boolean = false,
    var url: String? = null,
    var message: String? = null
)

@Group("example.suleyman.io")
@Version("v1alpha1")
class Session :
    CustomResource<SessionSpec, SessionStatus>(),
    Namespaced
