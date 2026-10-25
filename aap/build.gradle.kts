// The Android Auto Projection protocol, phone side: framing, TLS, control channel and service
// channels. Pure JVM so it can be unit-tested and driven by a simulated head unit on the desktop.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
            }
        }
    }
}

dependencies {
    api(libs.protobuf.javalite)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

// Desktop "phone" for testing against real head-unit software (Google's DHU, openauto...).
tasks.register<JavaExec>("runPhoneSim") {
    group = "verification"
    description = "Serve AAP on TCP 5277 like the developer head-unit server and stream an H.264 file"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("net.prototux.aare.aap.sim.PhoneSimKt")
    maxHeapSize = "256m"
    workingDir = rootProject.projectDir
}

// Desktop head unit: drives a phone's head-unit server (adb forward tcp:5277 localabstract:aare_hu) and records it.
tasks.register<JavaExec>("runHeadUnit") {
    group = "verification"
    description = "Connect to a phone on localhost:5277 as a head unit and record the projection"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("net.prototux.aare.aap.sim.HeadUnitClientKt")
    maxHeapSize = "256m"
    workingDir = rootProject.projectDir
}
