import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder

fun controllerOwnerRef(owner: HasMetadata): OwnerReference =
    OwnerReferenceBuilder()
        .withApiVersion(owner.apiVersion)
        .withKind(owner.kind)
        .withName(owner.metadata.name)
        .withUid(owner.metadata.uid)
        .withController(true)
        .withBlockOwnerDeletion(true)
        .build()
