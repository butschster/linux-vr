plugins {
    id("com.android.application")
}

// The terminal client: a 2D window, like :panel and for the same reason — the
// Horizon OS shell places it, resizes it and lets it coexist with everything
// else. What is different is what fills the window: glyphs drawn here from the
// font, rather than a decoded video of glyphs drawn on the host.
android {
    namespace = "dev.butschster.linuxvr.term"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.butschster.linuxvr.term"
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

    lint {
        // The vendored emulator is upstream code; its warnings are not ours to fix.
        disable += setOf("UnusedResources")
    }
}
