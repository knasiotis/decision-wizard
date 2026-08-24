// AGP 9 has built-in Kotlin support. Applying org.jetbrains.kotlin.android on
// top of it is a hard error, not a warning.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Schemas are committed. A migration in v0.3 needs the previous version to diff
// against, and that is impossible to reconstruct after the fact.
room {
    schemaDirectory("$projectDir/schemas")
}

// Read through providers, not System.getenv/findProperty directly: the root
// gradle.properties turns the configuration cache on, and direct reads at
// configuration time invalidate it.
val keystorePath = providers.environmentVariable("KEYSTORE_PATH")

// The version comes from gradle.properties, not from the command line. F-Droid
// builds the tag with a plain `gradle assembleRelease` and passes no -P flags,
// so a CI-injected version would land in their APK as "dev" / 1 and the build
// would be rejected for not matching the recipe.
val appVersionName = providers.gradleProperty("appVersionName")

// Derived from the name so F-Droid, CI and a local release build cannot
// disagree about it: major*10000 + minor*100 + patch, the same arithmetic
// F-Droid's own tooling uses. Patch is therefore capped at 99.
//
// This replaced github.run_number, which was monotonic but existed nowhere in
// the repo — nobody outside CI could reproduce the number. 0.5.5 shipped as 33
// under the old scheme; 0.5.6 is 506, so upgrades still move forward.
fun versionCodeOf(name: String): Int {
    val parts = name.split(".")
    require(parts.size == 3) { "appVersionName must be major.minor.patch, got '$name'" }
    val (major, minor, patch) = parts.map { part ->
        part.toIntOrNull() ?: error("appVersionName must be numeric, got '$name'")
    }
    require(patch < 100) { "patch must be below 100, got '$name'" }
    return major * 10000 + minor * 100 + patch
}

android {
    namespace = "com.knasiotis.decisionwizard"
    // Forced by the dependencies, not chosen: Compose 1.12 / core-ktx 1.19 /
    // lifecycle 2.11 all refuse to be consumed below 37. Raising compileSdk only
    // permits newer APIs to be called; it is independent of targetSdk, which is
    // what actually opts the app into new runtime behaviour.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.knasiotis.decisionwizard"
        // 31 so Material You dynamic colour needs no version guard. Everything
        // else in the app works fine lower; this is the only reason.
        minSdk = 31
        // Deliberately one below compileSdk. Bumping this opts into API 37's
        // runtime behaviour changes, and the app has never run on a device yet.
        // Raise it once v0.1 has actually been installed and tried.
        targetSdk = 36
        versionName = appVersionName.get()
        versionCode = versionCodeOf(appVersionName.get())
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
        // Off by default since AGP 8. Settings > About reads the version from it.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // No bundled sample graph. v0.1 shipped one because there was no library to
    // put anything in; from v0.2 a fresh install starts empty and the empty
    // state points at import. samples/ lives on as the :graphcore test fixture.
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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    debugImplementation(libs.compose.ui.tooling)
}
