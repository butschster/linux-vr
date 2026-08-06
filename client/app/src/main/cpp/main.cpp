// Milestone B: a frame from MediaCodec into a compositor cylinder layer.
//
// There is no network yet — frames come from a local file. What is being
// verified is the one thing that matters: the decoder -> surface swapchain ->
// composition layer path, and the text readability on that layer, which is
// what the whole project is about.
//
// In milestone C VideoDecoder is swapped for a live receiver. The rest of the
// code stays as it is: both have the same output, one ANativeWindow.

#include <android_native_app_glue.h>

#include <string>

#include "log.h"
#include "video_decoder.h"
#include "xr_app.h"

namespace {

// Layer resolution. 1440p is not "small enough to fit" but a deliberate
// ceiling: Quest 3 offers ~1680x1760 per eye, and a 50-degree-wide monitor
// physically cannot show more than ~1250 horizontal pixels. The exact figure
// is better taken from the runtime via XR_META_recommended_layer_resolution,
// which the device does expose.
constexpr uint32_t kLayerWidth = 2560;
constexpr uint32_t kLayerHeight = 1440;

// A screenshot of a real Ubuntu desktop at stock settings (scale 1.0,
// UI Ubuntu Sans 11 ~= 14.7 px, terminal Ubuntu Sans Mono 13 ~= 17.3 px).
// The synthetic size ladder lives next to it as testpattern.mp4.
constexpr const char *kTestPatternName = "desktop.mp4";

struct AppState {
    XrApp xr;
    VideoDecoder decoder;
    bool resumed = false;
    bool decoderStarted = false;
};

void onAppCmd(android_app *app, int32_t cmd) {
    auto *state = static_cast<AppState *>(app->userData);
    if (state == nullptr) return;

    switch (cmd) {
        case APP_CMD_RESUME:
            state->resumed = true;
            LOGI("activity resumed");
            break;
        case APP_CMD_PAUSE:
            state->resumed = false;
            LOGI("activity paused");
            break;
        case APP_CMD_INIT_WINDOW:
            LOGI("activity window created");
            break;
        case APP_CMD_TERM_WINDOW:
            LOGI("activity window destroyed");
            break;
        case APP_CMD_DESTROY:
            LOGI("activity being destroyed");
            state->decoder.stop();
            break;
        default:
            break;
    }
}

}  // namespace

void android_main(android_app *app) {
    LOGI("======== linux-vr, milestone B ========");

    AppState state;
    app->userData = &state;
    app->onAppCmd = onAppCmd;

    if (!state.xr.init(app)) {
        LOGE("OpenXR init failed, exiting");
        return;
    }

    state.xr.geometry.radius = 1.5f;
    // 66 degrees follows from the measurement: at this angle Ubuntu's stock
    // font (~15 px) lands exactly on the measured comfort threshold of 0.39
    // degrees. See docs/readability.md.
    state.xr.geometry.horizontalFovDegrees = 66.0f;
    state.xr.geometry.heightOffset = 0.0f;

    ANativeWindow *window = state.xr.createVideoSurface(kLayerWidth, kLayerHeight);
    if (window == nullptr) {
        LOGE("surface swapchain creation failed, exiting");
        state.xr.shutdown();
        return;
    }

    // The app's external files directory — where the test material goes.
    // Unlike shared /sdcard it needs no permission.
    const std::string path = std::string(app->activity->externalDataPath) + "/" + kTestPatternName;
    LOGI("looking for test file: %s", path.c_str());

    while (!app->destroyRequested) {
        // Drain the Android event queue.
        //
        // Blocking here is allowed ONLY while the activity is paused and the
        // session is not running: session state arrives through xrPollEvent,
        // not through the Android looper. Block with a -1 timeout at the moment
        // the runtime is about to send READY and you will wait forever, because
        // you are waiting on the wrong channel.
        for (;;) {
            const int timeout =
                (!state.resumed && !state.xr.sessionRunning() && !app->destroyRequested) ? -1 : 0;

            int events = 0;
            android_poll_source *source = nullptr;
            if (ALooper_pollOnce(timeout, nullptr, &events,
                                 reinterpret_cast<void **>(&source)) < 0) {
                break;
            }
            if (source != nullptr) source->process(app, source);
        }

        if (!state.xr.pollEvents(app)) break;

        // Start the decoder only once the session is actually running,
        // otherwise the first frames go nowhere and the counter lies.
        if (state.xr.sessionRunning() && !state.decoderStarted) {
            if (state.decoder.start(path, window)) {
                state.decoderStarted = true;
                LOGI("decoder started");
            } else {
                LOGE("decoder did not start — is the file there? %s", path.c_str());
                state.decoderStarted = true;  // do not retry every frame
            }
        }

        state.xr.renderFrame();
    }

    LOGI("frames delivered by decoder: %llu",
         static_cast<unsigned long long>(state.decoder.framesRendered()));

    state.decoder.stop();
    state.xr.shutdown();
    LOGI("======== shutting down ========");
}
