#include "probe.h"

#include <android_native_app_glue.h>

#define XR_USE_PLATFORM_ANDROID
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <cstring>
#include <vector>

#include "log.h"

namespace {

// Extensions that architectural decisions depend on.
// Listed as strings rather than macros: some macros sit behind platform guards,
// and a typo is better seen in the report than in a compile error.
struct Watched {
    const char *name;
    const char *why;
};

const Watched kWatched[] = {
    {"XR_KHR_android_surface_swapchain", "critical: MediaCodec -> Surface -> compositor"},
    {"XR_KHR_composition_layer_cylinder", "critical: curved layer instead of a quad"},
    {"XR_KHR_opengl_es_enable", "graphics backend"},
    {"XR_KHR_android_create_instance", "instance creation on Android"},
    {"XR_KHR_composition_layer_equirect2", "fallback for very wide layouts"},
    {"XR_FB_composition_layer_settings", "layer sharpening/supersampling — matters for text"},
    {"XR_FB_composition_layer_alpha_blend", "blending with passthrough"},
    {"XR_FB_display_refresh_rate", "change display rate at runtime"},
    {"XR_META_recommended_layer_resolution", "runtime reports the optimal layer resolution"},
    {"XR_FB_swapchain_update_state_android_surface", "resize without recreating"},
    {"XR_META_performance_metrics", "compositor telemetry for measurements"},
};

bool isSupported(const std::vector<XrExtensionProperties> &all, const char *name,
                 uint32_t *versionOut) {
    for (const auto &e : all) {
        if (std::strcmp(e.extensionName, name) == 0) {
            if (versionOut) *versionOut = e.extensionVersion;
            return true;
        }
    }
    return false;
}

std::vector<XrExtensionProperties> enumerateExtensions() {
    uint32_t count = 0;
    if (XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr))) return {};
    std::vector<XrExtensionProperties> props(count, {XR_TYPE_EXTENSION_PROPERTIES});
    if (XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr, count, &count, props.data()))) {
        return {};
    }
    props.resize(count);
    return props;
}

void reportSystem(android_app *app, const std::vector<XrExtensionProperties> &all) {
    std::vector<const char *> enabled;
    for (const char *name : {"XR_KHR_android_create_instance", "XR_KHR_opengl_es_enable"}) {
        if (isSupported(all, name, nullptr)) enabled.push_back(name);
    }

    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidInfo.applicationVM = app->activity->vm;
    androidInfo.applicationActivity = app->activity->clazz;

    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    ci.next = &androidInfo;
    ci.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
    ci.enabledExtensionNames = enabled.data();
    std::strcpy(ci.applicationInfo.applicationName, "linux-vr-probe");
    ci.applicationInfo.applicationVersion = 1;
    std::strcpy(ci.applicationInfo.engineName, "none");
    ci.applicationInfo.engineVersion = 1;
    ci.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;

    XrInstance instance = XR_NULL_HANDLE;
    if (XR_FAILED(xrCreateInstance(&ci, &instance))) {
        LOGE("probe: xrCreateInstance failed");
        return;
    }

    XrInstanceProperties ip{XR_TYPE_INSTANCE_PROPERTIES};
    if (XR_SUCCEEDED(xrGetInstanceProperties(instance, &ip))) {
        LOGI("runtime: %s  v%u.%u.%u", ip.runtimeName, XR_VERSION_MAJOR(ip.runtimeVersion),
             XR_VERSION_MINOR(ip.runtimeVersion), XR_VERSION_PATCH(ip.runtimeVersion));
    }

    XrSystemGetInfo sysInfo{XR_TYPE_SYSTEM_GET_INFO};
    sysInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    if (XR_FAILED(xrGetSystem(instance, &sysInfo, &systemId))) {
        LOGE("probe: xrGetSystem failed");
        xrDestroyInstance(instance);
        return;
    }

    XrSystemProperties sp{XR_TYPE_SYSTEM_PROPERTIES};
    if (XR_SUCCEEDED(xrGetSystemProperties(instance, systemId, &sp))) {
        LOGI("system: %s", sp.systemName);
        LOGI("  swapchain limit: %ux%u, layers per composition: %u",
             sp.graphicsProperties.maxSwapchainImageWidth,
             sp.graphicsProperties.maxSwapchainImageHeight, sp.graphicsProperties.maxLayerCount);
    }

    uint32_t viewCount = 0;
    xrEnumerateViewConfigurationViews(instance, systemId,
                                      XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &viewCount,
                                      nullptr);
    if (viewCount > 0) {
        std::vector<XrViewConfigurationView> views(viewCount, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
        if (XR_SUCCEEDED(xrEnumerateViewConfigurationViews(
                instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, viewCount,
                &viewCount, views.data()))) {
            LOGI("  per eye: recommended %ux%u, maximum %ux%u",
                 views[0].recommendedImageRectWidth, views[0].recommendedImageRectHeight,
                 views[0].maxImageRectWidth, views[0].maxImageRectHeight);
        }
    }

    xrDestroyInstance(instance);
}

}  // namespace

void runCapabilityProbe(android_app *app) {
    LOGI("======== capability probe ========");

    const auto extensions = enumerateExtensions();
    if (extensions.empty()) {
        LOGE("failed to enumerate extensions");
        return;
    }

    LOGI("runtime exposes %zu extensions", extensions.size());
    for (const auto &w : kWatched) {
        uint32_t v = 0;
        if (isSupported(extensions, w.name, &v)) {
            LOGI("  [ OK ] %-46s v%-3u  %s", w.name, v, w.why);
        } else {
            LOGE("  [MISS] %-46s        %s", w.name, w.why);
        }
    }

    reportSystem(app, extensions);
    LOGI("======== probe done ========");
}
