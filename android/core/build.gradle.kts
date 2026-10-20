plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Bytecode level the Android app consumes; any JDK 17+ can build it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // Shared vectors live at <repo>/protocol/vectors.
    systemProperty("handspan.vectors", rootProject.file("../protocol/vectors").absolutePath)
    // Set by scripts/interop.sh to run the cross-implementation test against the Swift host.
    System.getenv("HANDSPAN_HOST_BIN")?.let { systemProperty("handspan.hostBin", it) }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
