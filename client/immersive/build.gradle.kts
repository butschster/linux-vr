plugins {
    id("com.android.application")
}

// The immersive OpenXR client. Its own application id so it can sit on the
// headset next to the app people actually use: two APKs cannot share one.
android {
    namespace = "dev.butschster.linuxvr.immersive"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.butschster.linuxvr.immersive"
        // Quest 3 / 3S run Android 12L
        minSdk = 32
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

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
