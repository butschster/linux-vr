plugins {
    id("com.android.application")
}

// The panel variant: an ordinary 2D Android app.
//
// Horizon OS shows it as a window alongside every other app, which is the whole
// point — placement, resizing and coexistence with Discord or a browser come
// from the shell instead of being reimplemented here. The immersive OpenXR
// client (:app) keeps direct control of the composition layer and stays around
// for measurements; this one trades that control for multitasking.
android {
    namespace = "dev.butschster.linuxvr.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.butschster.linuxvr.panel"
        minSdk = 32
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
