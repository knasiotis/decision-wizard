// AGP 9 has built-in Kotlin support. Applying org.jetbrains.kotlin.android on
// top of it is a hard error, not a warning.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Read through providers, not System.getenv/findProperty directly: the root
// gradle.properties turns the configuration cache on, and direct reads at
// configuration time invalidate it.
val keystorePath = providers.environmentVariable("KEYSTORE_PATH")
val buildVersionName = providers.gradleProperty("versionName")
val buildVersionCode = providers.gradleProperty("versionCode")

android {
    namespace = "com.knasiotis.decisionwizard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.knasiotis.decisionwizard"
        // 31 so Material You dynamic colour needs no version guard. Everything
        // else in the app works fine lower; this is the only reason.
        minSdk = 31
        targetSdk = 36
        versionCode = buildVersionCode.orNull?.toInt() ?: 1
        versionName = buildVersionName.orNull ?: "dev"
    }

    // Only exists in CI, where release.yml decodes the keystore into RUNNER_TEMP.
    // A local release build is simply unsigned rather than a configuration error.
    signingConfigs {
        if (keystorePath.isPresent) {
            create("release") {
                storeFile = file(keystorePath.get())
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // The single canonical copy of the sample graph, shared with :graphcore's
    // test resources rather than duplicated here.
    sourceSets["main"].assets.directories.add(rootProject.file("samples").absolutePath)
}

dependencies {
    implementation(project(":graphcore"))
    // :graphcore keeps serialization as `implementation`, so the runtime is not
    // on this module's compile classpath. ChatScreen needs it to save session
    // state across configuration changes.
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    debugImplementation(libs.compose.ui.tooling)
}
