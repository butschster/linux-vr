plugins {
    id("com.android.application")
}

// The immersive OpenXR client. Its own application id so it can sit on the
// headset next to the app people actually use: two APKs cannot share one.
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
    namespace = "dev.butschster.linuxvr.immersive"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.butschster.linuxvr.immersive"
        // Quest 3 / 3S run Android 12L
        minSdk = 32
        targetSdk = 34
        versionCode = versionCodeOf(releaseVersion)
        versionName = releaseVersion

        ndk {
            // Quest is arm64 only; other ABIs are dead weight
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        // The Khronos loader AAR arrives as a prefab package
        prefab = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Official Khronos loader — Meta's vendor SDK is not needed
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.62")
}
