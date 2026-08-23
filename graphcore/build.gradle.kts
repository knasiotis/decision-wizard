plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure JVM on purpose. This module must never see the Android framework, so that
// the layout engine, validator, undo stack and delete ops stay unit-testable
// without an emulator or Robolectric. Being a separate module makes that a
// compile error rather than a code-review catch.
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The sample graph is shared: it is the test fixture here and the bundled asset
// in :app. One canonical copy in samples/, referenced from both.
sourceSets["test"].resources.srcDir(rootProject.file("samples"))

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
