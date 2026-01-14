plugins {
    kotlin("jvm") version "2.1.20"
    id("application")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Java Operator SDK
    implementation("io.javaoperatorsdk:operator-framework:5.1.4")

    // Kubernetes client
    implementation("io.fabric8:kubernetes-client:7.3.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Velocity Template
    implementation("org.apache.velocity:velocity-engine-core:2.3")


}

// Main setting for operator
application {
    mainClass.set("com.globalmaksimum.operator.MainKt")
}



tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

