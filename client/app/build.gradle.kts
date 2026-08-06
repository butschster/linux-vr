plugins {
    id("com.android.application")
}

android {
    namespace = "dev.butschster.linuxvr"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.butschster.linuxvr"
        // Quest 3 / 3S работают на Android 12L
        minSdk = 32
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        ndk {
            // Quest — только arm64, остальные ABI лишний вес
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
        // AAR с loader'ом от Khronos приезжает как prefab-пакет
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
    // Официальный loader от Khronos — вендорский SDK от Meta не нужен
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.62")
}
