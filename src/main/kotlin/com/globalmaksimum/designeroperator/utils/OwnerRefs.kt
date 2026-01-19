package com.globalmaksimum.designeroperator.utils

import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder

object OwnerRefs {

    fun controllerOwnerRef(owner: HasMetadata): OwnerReference =
        OwnerReferenceBuilder()
            .withApiVersion(owner.apiVersion)
            .withKind(owner.kind)
            .withName(owner.metadata.name)
            .withUid(owner.metadata.uid)
            .withController(true)
            .withBlockOwnerDeletion(true)
            .build()

    fun ownerRef(owner: HasMetadata): OwnerReference =
        OwnerReferenceBuilder()
            .withApiVersion(owner.apiVersion)
            .withKind(owner.kind)
            .withName(owner.metadata.name)
            .withUid(owner.metadata.uid)
            .build()
}
