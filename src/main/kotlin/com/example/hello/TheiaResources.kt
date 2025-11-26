package com.example.hello

import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Version

// ===== AppDefinition =====

class EnvVarSpec {
    var name: String = ""
    var value: String? = null
}

class AppDefinitionSpec {
    var image: String = ""
    var port: Int = 3000
    var uid: Int? = null

    var requestsCpu: String? = null
    var requestsMemory: String? = null
    var limitsCpu: String? = null
    var limitsMemory: String? = null

    var env: List<EnvVarSpec>? = null
    var mountPath: String? = "/home/project"
}

class AppDefinitionStatus {
    var ready: Boolean = false
    var message: String? = null
}

@Group("example.suleyman.io")
@Version("v1alpha1")
class AppDefinition :
    CustomResource<AppDefinitionSpec, AppDefinitionStatus>(),
    Namespaced


// ===== Workspace =====

class WorkspaceSpec {
    var owner: String? = null
    var appDefinitionName: String? = null
    var label: String? = null          // user field in theia cloud
    var storageSize: String = "5Gi"
    var storageClassName: String? = null
}

class WorkspaceStatus {
    var ready: Boolean = false
    var message: String? = null
}

@Group("example.suleyman.io")
@Version("v1alpha1")
class Workspace :
    CustomResource<WorkspaceSpec, WorkspaceStatus>(),
    Namespaced


// ===== Session =====

class SessionSpec {
    var workspaceName: String? = null
    var appDefinitionName: String? = null
    var user: String? = null
    var envVars: Map<String, String>? = null
    var envVarsFromConfigMaps: List<String>? = null
    var envVarsFromSecrets: List<String>? = null
}

class SessionStatus {
    var ready: Boolean = false
    var url: String? = null
    var message: String? = null
}

@Group("example.suleyman.io")
@Version("v1alpha1")
class Session :
    CustomResource<SessionSpec, SessionStatus>(),
    Namespaced
