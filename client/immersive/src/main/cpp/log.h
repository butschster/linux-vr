#pragma once

#include <android/log.h>

#define LV_TAG "linux-vr"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LV_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LV_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LV_TAG, __VA_ARGS__)

// Check an OpenXR result. Logs and returns false on failure.
#define XR_CHECK(expr)                                                  \
    ([&]() -> bool {                                                    \
        XrResult _r = (expr);                                           \
        if (XR_FAILED(_r)) {                                            \
            LOGE("%s -> %d  (%s:%d)", #expr, _r, __FILE__, __LINE__);   \
            return false;                                               \
        }                                                               \
        return true;                                                    \
    }())
