package com.example.hello

import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Version

// ===== AppDefinition =====

class AppDefinitionSpec {
    var name: String? = null
    var image: String? = null
    var imagePullPolicy: String? = null
    var pullSecret: String? = null
    var uid: Int? = null
    var port: Int? = null
    var ingressname: String? = null
    var ingressHostnamePrefixes: List<String>? = null
    var minInstances: Int? = null
    var maxInstances: Int? = null
    var timeout: Int? = null
    var requestsMemory: String? = null
    var requestsCpu: String? = null
    var limitsMemory: String? = null
    var limitsCpu: String? = null
    var downlinkLimit: Int? = null
    var uplinkLimit: Int? = null
    var mountPath: String? = null
    var monitor: MonitorSpec? = null
    var options: Map<String, Any>? = null
}

class MonitorSpec {
    var port: Int? = null
    var activityTracker: ActivityTrackerSpec? = null
}

class ActivityTrackerSpec {
    var timeoutAfter: Int? = null
    var notifyAfter: Int? = null
}

class AppDefinitionStatus {
    var operatorStatus: String? = null
    var operatorMessage: String? = null
}


@Group("example.suleyman.io")
@Version("v1")
class AppDefinition :
    CustomResource<AppDefinitionSpec, AppDefinitionStatus>(),
    Namespaced


// ===== Workspace =====

class WorkspaceSpec {
    var name: String? = null              // required by CRD
    var label: String? = null             // optional
    var appDefinition: String? = null     // optional in CRD
    var user: String? = null              // required by CRD
    var storage: String? = null           // optional
    var options: Map<String, String>? = null // matches x-kubernetes-int-or-string
}

class VolumeStatus(
    var status: String? = null,
    var message: String? = null
)

class WorkspaceStatus {
    var operatorStatus: String? = null
    var operatorMessage: String? = null

    var volumeClaim: VolumeStatus? = null
    var volumeAttach: VolumeStatus? = null

    var error: String? = null

}

@Group("example.suleyman.io")
@Version("v1")
class Workspace :
    CustomResource<WorkspaceSpec, WorkspaceStatus>(),
    Namespaced



// ===== Session =====

class SessionSpec {
    var name: String? = null                 // required by CRD
    var workspace: String? = null            // workspace name
    var appDefinition: String? = null        // required by CRD
    var user: String? = null                 // required by CRD

    var sessionSecret: String? = null        // optional

    // Matches x-kubernetes-int-or-string, you can keep it as String for simplicity
    var options: Map<String, String>? = null
    var envVars: Map<String, String>? = null

    var envVarsFromConfigMaps: List<String>? = null
    var envVarsFromSecrets: List<String>? = null
}

class SessionStatus {
    var operatorStatus: String? = null
    var operatorMessage: String? = null
    var url: String? = null
    var error: String? = null
    var lastActivity: Long? = null           // integer in CRD, Long in Kotlin
}

@Group("example.suleyman.io")
@Version("v1")
class Session :
    CustomResource<SessionSpec, SessionStatus>(),
    Namespaced
