#include "probe.h"

#include <android_native_app_glue.h>

#define XR_USE_PLATFORM_ANDROID
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <cstring>
#include <vector>

#include "log.h"

namespace {

// Расширения, от наличия которых зависят архитектурные решения.
// Строками, а не макросами: часть макросов спрятана за platform-гардами,
// и промах в имени лучше увидеть в отчёте, чем в ошибке компиляции.
struct Watched {
    const char *name;
    const char *why;
};

const Watched kWatched[] = {
    {"XR_KHR_android_surface_swapchain", "критично: MediaCodec -> Surface -> компоситор"},
    {"XR_KHR_composition_layer_cylinder", "критично: изогнутый слой вместо quad"},
    {"XR_KHR_opengl_es_enable", "графический бэкенд"},
    {"XR_KHR_android_create_instance", "создание инстанса на Android"},
    {"XR_KHR_composition_layer_equirect2", "запасной вариант широкого лейаута"},
    {"XR_FB_composition_layer_settings", "резкость/суперсэмплинг слоя — важно для текста"},
    {"XR_FB_composition_layer_alpha_blend", "смешивание с passthrough"},
    {"XR_FB_display_refresh_rate", "смена частоты дисплея на лету"},
    {"XR_META_recommended_layer_resolution", "рантайм сам скажет оптимальное разрешение слоя"},
    {"XR_FB_swapchain_update_state_android_surface", "смена разрешения без пересоздания"},
    {"XR_META_performance_metrics", "телеметрия компоситора для замеров"},
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
        LOGE("проба: xrCreateInstance провалился");
        return;
    }

    XrInstanceProperties ip{XR_TYPE_INSTANCE_PROPERTIES};
    if (XR_SUCCEEDED(xrGetInstanceProperties(instance, &ip))) {
        LOGI("рантайм: %s  v%u.%u.%u", ip.runtimeName, XR_VERSION_MAJOR(ip.runtimeVersion),
             XR_VERSION_MINOR(ip.runtimeVersion), XR_VERSION_PATCH(ip.runtimeVersion));
    }

    XrSystemGetInfo sysInfo{XR_TYPE_SYSTEM_GET_INFO};
    sysInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    if (XR_FAILED(xrGetSystem(instance, &sysInfo, &systemId))) {
        LOGE("проба: xrGetSystem провалился");
        xrDestroyInstance(instance);
        return;
    }

    XrSystemProperties sp{XR_TYPE_SYSTEM_PROPERTIES};
    if (XR_SUCCEEDED(xrGetSystemProperties(instance, systemId, &sp))) {
        LOGI("система: %s", sp.systemName);
        LOGI("  предел swapchain: %ux%u, слоёв в композиции: %u",
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
            LOGI("  на глаз: рекомендовано %ux%u, максимум %ux%u",
                 views[0].recommendedImageRectWidth, views[0].recommendedImageRectHeight,
                 views[0].maxImageRectWidth, views[0].maxImageRectHeight);
        }
    }

    xrDestroyInstance(instance);
}

}  // namespace

void runCapabilityProbe(android_app *app) {
    LOGI("======== проба возможностей ========");

    const auto extensions = enumerateExtensions();
    if (extensions.empty()) {
        LOGE("перечислить расширения не удалось");
        return;
    }

    LOGI("рантайм отдаёт %zu расширений", extensions.size());
    for (const auto &w : kWatched) {
        uint32_t v = 0;
        if (isSupported(extensions, w.name, &v)) {
            LOGI("  [ЕСТЬ] %-46s v%-3u  %s", w.name, v, w.why);
        } else {
            LOGE("  [ НЕТ] %-46s        %s", w.name, w.why);
        }
    }

    reportSystem(app, extensions);
    LOGI("======== проба завершена ========");
}
