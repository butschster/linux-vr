pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "linux-vr-client"

// The app. One launcher entry, a server manager behind it, and a window per
// screen once you connect.
include(":app")

// The immersive OpenXR client. Not what anyone should install: it takes the
// whole headset instead of sitting beside other apps. It stays because it keeps
// full control of the composition layer, which is what any future work on
// multiple monitor layers will need, and because every measurement in docs/ was
// made with it.
include(":immersive")
