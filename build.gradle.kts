plugins {
    kotlin("jvm") version "2.1.20"
    id("application")
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.globalmaksimum"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.javaoperatorsdk:operator-framework:5.1.4")
    implementation("io.fabric8:kubernetes-client:7.3.1")
    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("org.apache.velocity:velocity-engine-core:2.3")
}

application {
    mainClass.set("com.globalmaksimum.operator.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("operator")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.globalmaksimum.operator.MainKt"
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

