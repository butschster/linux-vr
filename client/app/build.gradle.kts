plugins {
    id("com.android.application")
}

// The client: an ordinary 2D Android app.
//
// Horizon OS shows it as a window alongside every other app, which is the whole
// point — placement, resizing and coexistence with a browser come from the
// shell instead of being reimplemented here. The immersive OpenXR client
// (:immersive) keeps direct control of the composition layer and stays around
// for measurements; this one trades that control for multitasking.
// The version comes from the tag, not from a number edited by hand.
//
// A release whose APK reports a different version than the tag it was cut from
// is a release you cannot reason about — this shipped once as v1.0.0 with
// versionName 0.2, and nothing caught it until the APK was installed and asked.
//
// versionCode has to increase for Android to accept an update, and it cannot be
// derived from a semantic version without a rule, so the rule is here:
// major * 10000 + minor * 100 + patch.
val releaseVersion: String = (project.findProperty("linuxvrVersion") as String?)
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: "0.0.0-dev"

fun versionCodeOf(version: String): Int {
    val parts = version.substringBefore("-").split(".")
    fun at(i: Int) = parts.getOrNull(i)?.toIntOrNull() ?: 0
    return maxOf(1, at(0) * 10000 + at(1) * 100 + at(2))
}

android {
    namespace = "dev.butschster.linuxvr"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.butschster.linuxvr"
        minSdk = 32
        targetSdk = 34
        versionCode = versionCodeOf(releaseVersion)
        versionName = releaseVersion
    }

    // A release key when one is supplied, the debug key when none is.
    //
    // This is not ceremony. Android refuses to install an update signed by a
    // different key than the installed copy, and a debug keystore is generated
    // per machine — so without a stable key, every release would have to be
    // uninstalled before the next one could be installed. The key lives outside
    // the repository; see docs/server.md.
    val keystore = file(
        providers.environmentVariable("ANDROID_KEYSTORE").orNull
            ?: "${System.getProperty("user.home")}/.config/linuxvr/release.keystore"
    )
    val keystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull

    signingConfigs {
        if (keystore.exists() && keystorePassword != null) {
            create("release") {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull ?: "linux-vr"
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
                    ?: keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
