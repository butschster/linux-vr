#pragma once

#include <android/native_window.h>
#include <jni.h>

// Order matters: openxr_platform.h declares structures with EGLDisplay,
// EGLConfig and EGLContext fields but does not pull in the EGL headers itself.
// Including it first gives a guaranteed wall of "unknown type name" errors.
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <string>
#include <vector>

#include "egl_context.h"
#include "input_sender.h"

struct android_app;

// Geometry of the virtual monitor.
//
// A cylinder rather than a plane: a wide layout gets less distortion at the
// edges and a constant accommodation distance, so the eye does not have to
// refocus when looking from the centre to the side.
struct LayerGeometry {
    // Distance to the surface, metres. 1.2-1.8 is the working range: further
    // away loses angular resolution, closer starts a vergence-accommodation
    // conflict during long sessions.
    float radius = 1.5f;

    // Horizontal angular width, degrees. See docs/readability.md for why 66
    // is the default rather than 50.
    float horizontalFovDegrees = 66.0f;

    // Aspect ratio of the source
    float aspectRatio = 16.0f / 9.0f;

    // Vertical offset of the centre relative to the reference point, metres
    float heightOffset = 0.0f;

    // Horizontal placement around the viewer, radians. The screen rotates
    // around the user rather than sliding sideways: for a curved surface that
    // is the only way to keep every point of it the same distance away.
    float yaw = 0.0f;
};

// An OpenXR session with a monitor layer.
//
// Renders no geometry at all: the scene is empty, there are zero objects.
// A frame arrives in the surface swapchain from the decoder and goes out to
// the compositor as a cylinder layer, bypassing the eye buffer and foveated
// rendering.
class XrApp {
public:
    bool init(android_app *app);
    void shutdown();

    // Creates a swapchain backed by an Android Surface. The returned window is
    // handed to the decoder, after which frames reach the compositor without a
    // single copy through system memory.
    ANativeWindow *createVideoSurface(uint32_t width, uint32_t height);

    // Connects the pointer to the host agent. Optional: without it the cursor
    // still draws in the headset, it just does not drive the desktop.
    void connectInput(const std::string &host, int port) { input_.connect(host, port); }

    // Processes runtime events. Returns false when it is time to quit.
    bool pollEvents(android_app *app);

    // One compositor frame. Call in a loop.
    void renderFrame();

    bool sessionRunning() const { return sessionRunning_; }

    LayerGeometry geometry;

private:
    bool initLoader(android_app *app);
    bool createInstance(android_app *app);
    bool createSession();
    void handleStateChange(const XrEventDataSessionStateChanged &ev);

    EglContext egl_;

    JavaVM *vm_ = nullptr;
    XrInstance instance_ = XR_NULL_HANDLE;
    XrSystemId systemId_ = XR_NULL_SYSTEM_ID;
    XrSession session_ = XR_NULL_HANDLE;
    XrSpace space_ = XR_NULL_HANDLE;
    XrSessionState state_ = XR_SESSION_STATE_UNKNOWN;
    XrEnvironmentBlendMode blendMode_ = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;

    // Passthrough: a screen floating in the actual room reads as a window,
    // while the same screen on black reads as a game that has taken over the
    // headset. On Meta runtimes this is not an environment blend mode but a
    // dedicated extension plus a layer submitted underneath everything else.
    XrPassthroughFB passthrough_ = XR_NULL_HANDLE;
    XrPassthroughLayerFB passthroughLayer_ = XR_NULL_HANDLE;
    bool passthroughEnabled_ = false;

    bool createPassthrough();
    bool sessionRunning_ = false;
    bool exitRequested_ = false;

    XrSwapchain videoSwapchain_ = XR_NULL_HANDLE;
    uint32_t videoWidth_ = 0;
    uint32_t videoHeight_ = 0;
    ANativeWindow *videoWindow_ = nullptr;

    // Input: currently only layer geometry tuning. The same action set will
    // carry push-to-talk for voice control.
    XrActionSet actionSet_ = XR_NULL_HANDLE;
    XrAction thumbstickAction_ = XR_NULL_HANDLE;
    XrAction resetAction_ = XR_NULL_HANDLE;
    XrAction aimAction_ = XR_NULL_HANDLE;
    XrAction gripAction_ = XR_NULL_HANDLE;
    XrAction recenterAction_ = XR_NULL_HANDLE;
    XrAction clickAction_ = XR_NULL_HANDLE;
    bool clickHeld_ = false;
    InputSender input_;
    XrSpace aimSpace_ = XR_NULL_HANDLE;
    XrSpace viewSpace_ = XR_NULL_HANDLE;
    bool actionsAttached_ = false;

    // Drag state, valid only while the grip is held
    bool dragging_ = false;
    float dragStartPointerYaw_ = 0.0f;
    float dragStartPointerHeight_ = 0.0f;
    float dragStartLayerYaw_ = 0.0f;
    float dragStartLayerHeight_ = 0.0f;

    void updateDrag(XrTime time, bool gripHeld);
    void recenter(XrTime time);

    // The cursor lives in its own layer rather than being drawn into the
    // desktop image. The compositor updates it at headset rate, so the pointer
    // stays instant regardless of stream lag.
    XrSwapchain cursorSwapchain_ = XR_NULL_HANDLE;
    std::vector<XrSwapchainImageOpenGLESKHR> cursorImages_;
    unsigned int cursorFbo_ = 0;
    bool cursorVisible_ = false;
    XrPosef cursorPose_{};
    float cursorU_ = 0.0f;
    float cursorV_ = 0.0f;

    bool createActions();
    bool createCursor();
    void applyInput(XrTime time);
    // Intersects the controller ray with the cylinder. Produces hit
    // coordinates in the 0..1 range — that is the mouse position on the desktop.
    void updateCursor(XrTime time);
    void drawCursorImage();

    PFN_xrCreateSwapchainAndroidSurfaceKHR xrCreateSwapchainAndroidSurfaceKHR_ = nullptr;
};
