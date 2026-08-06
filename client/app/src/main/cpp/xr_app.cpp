#include "xr_app.h"

#include <android/native_window_jni.h>
#include <android_native_app_glue.h>

#include <cmath>
#include <cstring>
#include <vector>

#include "log.h"
#include "probe.h"

namespace {

constexpr float kPi = 3.14159265358979323846f;

// Extensions without which the application has no point.
// Each one was confirmed present by the milestone A probe on Quest 3
// (see docs/device-probe.md).
const char *kRequiredExtensions[] = {
    "XR_KHR_android_create_instance",
    "XR_KHR_opengl_es_enable",
    "XR_KHR_android_surface_swapchain",
    "XR_KHR_composition_layer_cylinder",
    // MediaCodec writes into an Android Surface with the origin at the top
    // left; an OpenXR swapchain expects the opposite. Without this extension
    // the picture arrives flipped. Meta added it for exactly this case.
    "XR_FB_composition_layer_image_layout",
};

// Bounds for live geometry tuning with the stick
constexpr float kMinRadius = 0.8f;
constexpr float kMaxRadius = 3.0f;
constexpr float kMinFovDeg = 25.0f;
constexpr float kMaxFovDeg = 110.0f;
constexpr float kStickDeadzone = 0.25f;

// Cursor: 64x64 is plenty, the layer is a couple of centimetres across anyway
constexpr uint32_t kCursorSize = 64;
constexpr float kCursorMeters = 0.035f;

}  // namespace

bool XrApp::initLoader(android_app *app) {
    PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
    if (!XR_CHECK(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                                        reinterpret_cast<PFN_xrVoidFunction *>(&initializeLoader)))) {
        return false;
    }

    XrLoaderInitInfoAndroidKHR info{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    info.applicationVM = app->activity->vm;
    info.applicationContext = app->activity->clazz;
    return XR_CHECK(initializeLoader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR *>(&info)));
}

bool XrApp::createInstance(android_app *app) {
    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidInfo.applicationVM = app->activity->vm;
    androidInfo.applicationActivity = app->activity->clazz;

    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    ci.next = &androidInfo;
    ci.enabledExtensionCount = sizeof(kRequiredExtensions) / sizeof(kRequiredExtensions[0]);
    ci.enabledExtensionNames = kRequiredExtensions;
    std::strcpy(ci.applicationInfo.applicationName, "linux-vr");
    ci.applicationInfo.applicationVersion = 1;
    std::strcpy(ci.applicationInfo.engineName, "none");
    ci.applicationInfo.engineVersion = 1;
    ci.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;

    if (!XR_CHECK(xrCreateInstance(&ci, &instance_))) return false;

    XrSystemGetInfo sysInfo{XR_TYPE_SYSTEM_GET_INFO};
    sysInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    if (!XR_CHECK(xrGetSystem(instance_, &sysInfo, &systemId_))) return false;

    return XR_CHECK(xrGetInstanceProcAddr(
        instance_, "xrCreateSwapchainAndroidSurfaceKHR",
        reinterpret_cast<PFN_xrVoidFunction *>(&xrCreateSwapchainAndroidSurfaceKHR_)));
}

bool XrApp::createSession() {
    // The GL version requirements must be queried before creating the session,
    // otherwise the runtime is allowed to refuse.
    PFN_xrGetOpenGLESGraphicsRequirementsKHR getRequirements = nullptr;
    if (!XR_CHECK(xrGetInstanceProcAddr(
            instance_, "xrGetOpenGLESGraphicsRequirementsKHR",
            reinterpret_cast<PFN_xrVoidFunction *>(&getRequirements)))) {
        return false;
    }
    XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    if (!XR_CHECK(getRequirements(instance_, systemId_, &requirements))) return false;

    XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    binding.display = egl_.display();
    binding.config = egl_.config();
    binding.context = egl_.context();

    XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
    ci.next = &binding;
    ci.systemId = systemId_;
    if (!XR_CHECK(xrCreateSession(instance_, &ci, &session_))) return false;

    // LOCAL rather than STAGE: the monitor should stand where the person sits
    // and move with them on recenter.
    XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    return XR_CHECK(xrCreateReferenceSpace(session_, &spaceInfo, &space_));
}

bool XrApp::init(android_app *app) {
    vm_ = app->activity->vm;
    if (!initLoader(app)) return false;

    // Diagnostics run right after the loader: if something critical is missing
    // it should show up in the log before we try to create anything.
    runCapabilityProbe(app);

    if (!egl_.create()) return false;
    if (!createInstance(app)) return false;
    if (!createSession()) return false;
    if (!createActions()) return false;
    if (!createCursor()) return false;

    LOGI("OpenXR session created");
    return true;
}

ANativeWindow *XrApp::createVideoSurface(uint32_t width, uint32_t height) {
    if (xrCreateSwapchainAndroidSurfaceKHR_ == nullptr) return nullptr;

    // For a surface swapchain only width and height are filled in.
    // format, sampleCount, faceCount, arraySize and mipCount must stay zero:
    // the frame producer (MediaCodec) owns the contents, and the runtime
    // rejects non-zero values with XR_ERROR_VALIDATION_FAILURE.
    XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    ci.width = width;
    ci.height = height;

    jobject surface = nullptr;
    if (!XR_CHECK(xrCreateSwapchainAndroidSurfaceKHR_(session_, &ci, &videoSwapchain_, &surface))) {
        return nullptr;
    }

    // The Surface comes back as a jobject, so a JNIEnv for this thread is
    // needed. The android_main thread is created by native_app_glue and is not
    // attached to the JVM, so GetEnv here legitimately returns JNI_EDETACHED.
    JNIEnv *env = nullptr;
    if (vm_ == nullptr) {
        LOGE("JavaVM was not stored");
        return nullptr;
    }
    if (vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("AttachCurrentThread failed");
            return nullptr;
        }
    }

    videoWindow_ = ANativeWindow_fromSurface(env, surface);
    if (videoWindow_ == nullptr) {
        LOGE("ANativeWindow_fromSurface returned null");
        return nullptr;
    }

    videoWidth_ = width;
    videoHeight_ = height;
    geometry.aspectRatio = static_cast<float>(width) / static_cast<float>(height);

    LOGI("surface swapchain created: %ux%u", width, height);
    return videoWindow_;
}

// Controller input. For now it serves one purpose: tuning the layer geometry
// live, in the headset, instead of guessing at constants and rebuilding.
// The same action set will become the basis for voice-control push-to-talk.
bool XrApp::createActions() {
    XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
    std::strcpy(setInfo.actionSetName, "layout");
    std::strcpy(setInfo.localizedActionSetName, "Layout tuning");
    setInfo.priority = 0;
    if (!XR_CHECK(xrCreateActionSet(instance_, &setInfo, &actionSet_))) return false;

    XrActionCreateInfo stickInfo{XR_TYPE_ACTION_CREATE_INFO};
    std::strcpy(stickInfo.actionName, "adjust");
    std::strcpy(stickInfo.localizedActionName, "Adjust layer");
    stickInfo.actionType = XR_ACTION_TYPE_VECTOR2F_INPUT;
    if (!XR_CHECK(xrCreateAction(actionSet_, &stickInfo, &thumbstickAction_))) return false;

    XrActionCreateInfo resetInfo{XR_TYPE_ACTION_CREATE_INFO};
    std::strcpy(resetInfo.actionName, "reset");
    std::strcpy(resetInfo.localizedActionName, "Reset layout");
    resetInfo.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
    if (!XR_CHECK(xrCreateAction(actionSet_, &resetInfo, &resetAction_))) return false;

    // aim, not grip: aim is the pointing ray oriented the way a user expects
    // from a laser pointer, while grip follows the hand's grasp.
    XrActionCreateInfo aimInfo{XR_TYPE_ACTION_CREATE_INFO};
    std::strcpy(aimInfo.actionName, "aim");
    std::strcpy(aimInfo.localizedActionName, "Pointer ray");
    aimInfo.actionType = XR_ACTION_TYPE_POSE_INPUT;
    if (!XR_CHECK(xrCreateAction(actionSet_, &aimInfo, &aimAction_))) return false;

    XrActionCreateInfo gripInfo{XR_TYPE_ACTION_CREATE_INFO};
    std::strcpy(gripInfo.actionName, "grab");
    std::strcpy(gripInfo.localizedActionName, "Grab screen");
    gripInfo.actionType = XR_ACTION_TYPE_FLOAT_INPUT;
    if (!XR_CHECK(xrCreateAction(actionSet_, &gripInfo, &gripAction_))) return false;

    XrActionCreateInfo recenterInfo{XR_TYPE_ACTION_CREATE_INFO};
    std::strcpy(recenterInfo.actionName, "recenter");
    std::strcpy(recenterInfo.localizedActionName, "Focus view");
    recenterInfo.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
    if (!XR_CHECK(xrCreateAction(actionSet_, &recenterInfo, &recenterAction_))) return false;

    XrPath stickPath = XR_NULL_PATH;
    XrPath aPath = XR_NULL_PATH;
    XrPath bPath = XR_NULL_PATH;
    XrPath aimPath = XR_NULL_PATH;
    XrPath gripPath = XR_NULL_PATH;
    XrPath profilePath = XR_NULL_PATH;
    xrStringToPath(instance_, "/user/hand/right/input/thumbstick", &stickPath);
    xrStringToPath(instance_, "/user/hand/right/input/a/click", &aPath);
    xrStringToPath(instance_, "/user/hand/right/input/b/click", &bPath);
    xrStringToPath(instance_, "/user/hand/right/input/aim/pose", &aimPath);
    xrStringToPath(instance_, "/user/hand/right/input/squeeze/value", &gripPath);
    xrStringToPath(instance_, "/interaction_profiles/oculus/touch_controller", &profilePath);

    const XrActionSuggestedBinding bindings[] = {
        {thumbstickAction_, stickPath},
        {resetAction_, aPath},
        {recenterAction_, bPath},
        {aimAction_, aimPath},
        {gripAction_, gripPath},
    };
    XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    suggested.interactionProfile = profilePath;
    suggested.suggestedBindings = bindings;
    suggested.countSuggestedBindings = sizeof(bindings) / sizeof(bindings[0]);
    if (!XR_CHECK(xrSuggestInteractionProfileBindings(instance_, &suggested))) return false;

    XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attach.countActionSets = 1;
    attach.actionSets = &actionSet_;
    if (!XR_CHECK(xrAttachSessionActionSets(session_, &attach))) return false;

    // The ray space is created after the action set is attached
    XrActionSpaceCreateInfo spaceInfo{XR_TYPE_ACTION_SPACE_CREATE_INFO};
    spaceInfo.action = aimAction_;
    spaceInfo.poseInActionSpace.orientation.w = 1.0f;
    if (!XR_CHECK(xrCreateActionSpace(session_, &spaceInfo, &aimSpace_))) return false;

    // VIEW space tracks the head. Needed so focus view can put the screen
    // wherever the user is actually looking.
    XrReferenceSpaceCreateInfo viewInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    viewInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    viewInfo.poseInReferenceSpace.orientation.w = 1.0f;
    if (!XR_CHECK(xrCreateReferenceSpace(session_, &viewInfo, &viewSpace_))) return false;

    actionsAttached_ = true;
    LOGI("input ready: stick — distance/width, grip — drag, B — focus view, A — reset");
    return true;
}

// Focus view: put the screen straight in front of wherever the head is
// pointing, at eye level. Distance and width are left alone — the user tuned
// those deliberately, and resetting them here would be a surprise.
void XrApp::recenter(XrTime time) {
    XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(xrLocateSpace(viewSpace_, space_, time, &head))) return;

    const XrSpaceLocationFlags needed =
        XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    if ((head.locationFlags & needed) != needed) return;

    const XrQuaternionf &q = head.pose.orientation;
    const float fx = -2.0f * (q.x * q.z + q.w * q.y);
    const float fz = -(1.0f - 2.0f * (q.x * q.x + q.y * q.y));

    // Only the horizontal component of the gaze is used. Taking pitch into
    // account would tilt the screen, which is never what the user wants.
    geometry.yaw = std::atan2(fx, -fz);
    geometry.heightOffset = head.pose.position.y;

    LOGI("focus view: yaw %.0f deg, height %.2f m", geometry.yaw * 180.0f / kPi,
         geometry.heightOffset);
}

// Dragging follows the pointer ray rather than the hand position: the screen
// is several metres of arc away, so hand translation is a poor control for it
// while the ray direction maps onto the surface one to one.
void XrApp::updateDrag(XrTime time, bool gripHeld) {
    if (!gripHeld) {
        dragging_ = false;
        return;
    }

    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(xrLocateSpace(aimSpace_, space_, time, &loc))) return;
    const XrSpaceLocationFlags needed =
        XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    if ((loc.locationFlags & needed) != needed) return;

    const XrQuaternionf &q = loc.pose.orientation;
    const float dx = -2.0f * (q.x * q.z + q.w * q.y);
    const float dy = -2.0f * (q.y * q.z - q.w * q.x);
    const float dz = -(1.0f - 2.0f * (q.x * q.x + q.y * q.y));

    const float pointerYaw = std::atan2(dx, -dz);
    const float pointerHeight = dy * geometry.radius;

    if (!dragging_) {
        dragging_ = true;
        dragStartPointerYaw_ = pointerYaw;
        dragStartPointerHeight_ = pointerHeight;
        dragStartLayerYaw_ = geometry.yaw;
        dragStartLayerHeight_ = geometry.heightOffset;
        return;
    }

    float deltaYaw = pointerYaw - dragStartPointerYaw_;
    // Unwrap across the +/-pi seam, otherwise the screen jumps a full turn
    // when the ray crosses straight behind the user.
    while (deltaYaw > kPi) deltaYaw -= 2.0f * kPi;
    while (deltaYaw < -kPi) deltaYaw += 2.0f * kPi;

    geometry.yaw = dragStartLayerYaw_ + deltaYaw;
    geometry.heightOffset = dragStartLayerHeight_ + (pointerHeight - dragStartPointerHeight_);
}

bool XrApp::createCursor() {
    XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
    ci.format = GL_RGBA8;
    ci.sampleCount = 1;
    ci.width = kCursorSize;
    ci.height = kCursorSize;
    ci.faceCount = 1;
    ci.arraySize = 1;
    ci.mipCount = 1;
    if (!XR_CHECK(xrCreateSwapchain(session_, &ci, &cursorSwapchain_))) return false;

    uint32_t count = 0;
    if (!XR_CHECK(xrEnumerateSwapchainImages(cursorSwapchain_, 0, &count, nullptr))) return false;
    cursorImages_.resize(count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
    if (!XR_CHECK(xrEnumerateSwapchainImages(
            cursorSwapchain_, count, &count,
            reinterpret_cast<XrSwapchainImageBaseHeader *>(cursorImages_.data())))) {
        return false;
    }

    glGenFramebuffers(1, &cursorFbo_);
    LOGI("cursor layer created: %ux%u, %u buffers", kCursorSize, kCursorSize, count);
    return true;
}

// The crosshair is drawn with scissor rects and clears — no shaders, no
// geometry. For a cursor that is enough, and it is an order of magnitude less
// code.
void XrApp::drawCursorImage() {
    uint32_t index = 0;
    XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    if (XR_FAILED(xrAcquireSwapchainImage(cursorSwapchain_, &acquire, &index))) return;

    XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    wait.timeout = XR_INFINITE_DURATION;
    if (XR_FAILED(xrWaitSwapchainImage(cursorSwapchain_, &wait))) return;

    glBindFramebuffer(GL_FRAMEBUFFER, cursorFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           cursorImages_[index].image, 0);

    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glEnable(GL_SCISSOR_TEST);
    const int c = static_cast<int>(kCursorSize) / 2;
    const int arm = static_cast<int>(kCursorSize) / 2 - 4;
    const int th = 3;

    // Dark outline under the crosshair — without it a white cursor disappears
    // against a light desktop
    glClearColor(0.0f, 0.0f, 0.0f, 0.85f);
    glScissor(c - arm - 1, c - th, 2 * arm + 2, 2 * th);
    glClear(GL_COLOR_BUFFER_BIT);
    glScissor(c - th, c - arm - 1, 2 * th, 2 * arm + 2);
    glClear(GL_COLOR_BUFFER_BIT);

    glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
    glScissor(c - arm, c - th + 1, 2 * arm, 2 * th - 2);
    glClear(GL_COLOR_BUFFER_BIT);
    glScissor(c - th + 1, c - arm, 2 * th - 2, 2 * arm);
    glClear(GL_COLOR_BUFFER_BIT);

    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    xrReleaseSwapchainImage(cursorSwapchain_, &release);
}

void XrApp::updateCursor(XrTime time) {
    cursorVisible_ = false;
    if (aimSpace_ == XR_NULL_HANDLE) return;

    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(xrLocateSpace(aimSpace_, space_, time, &loc))) return;
    const XrSpaceLocationFlags needed =
        XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    if ((loc.locationFlags & needed) != needed) return;

    const XrVector3f p = loc.pose.position;
    const XrQuaternionf q = loc.pose.orientation;

    // Ray direction is the -Z axis rotated by the controller orientation
    const float dx = -2.0f * (q.x * q.z + q.w * q.y);
    const float dy = -2.0f * (q.y * q.z - q.w * q.x);
    const float dz = -(1.0f - 2.0f * (q.x * q.x + q.y * q.y));

    // Intersection with an infinite cylinder of radius R around the Y axis.
    // The viewer is inside, so the far root is the one we want.
    const float R = geometry.radius;
    const float a = dx * dx + dz * dz;
    if (a < 1e-6f) return;
    const float b = 2.0f * (p.x * dx + p.z * dz);
    const float c = p.x * p.x + p.z * p.z - R * R;
    const float disc = b * b - 4.0f * a * c;
    if (disc < 0.0f) return;
    const float t = (-b + std::sqrt(disc)) / (2.0f * a);
    if (t <= 0.0f) return;

    const float hx = p.x + t * dx;
    const float hy = p.y + t * dy;
    const float hz = p.z + t * dz;

    // The hit angle is measured relative to where the layer centre faces,
    // which is the layer's own yaw and not necessarily straight ahead.
    float angle = std::atan2(hx, -hz) - geometry.yaw;
    while (angle > kPi) angle -= 2.0f * kPi;
    while (angle < -kPi) angle += 2.0f * kPi;

    const float halfAngle = geometry.horizontalFovDegrees * kPi / 180.0f * 0.5f;
    if (angle < -halfAngle || angle > halfAngle) return;

    // Layer height in metres follows from the arc length and the aspect ratio
    const float arc = R * (2.0f * halfAngle);
    const float heightMeters = arc / geometry.aspectRatio;
    const float dyFromCenter = hy - geometry.heightOffset;
    if (dyFromCenter < -heightMeters * 0.5f || dyFromCenter > heightMeters * 0.5f) return;

    // 0..1 fractions — the future mouse coordinates on the desktop
    cursorU_ = 0.5f + angle / (2.0f * halfAngle);
    cursorV_ = 0.5f - dyFromCenter / heightMeters;

    // Sit slightly in front of the surface, otherwise the compositor can
    // produce z-fighting
    const float lift = 0.01f;
    const float scale = (R - lift) / R;
    cursorPose_.position = {hx * scale, hy, hz * scale};

    // Turn the cursor plane to face the axis of the cylinder. The angle here
    // must be the world one, so the layer's own yaw goes back in.
    const float yaw = -(angle + geometry.yaw);
    cursorPose_.orientation = {0.0f, std::sin(yaw * 0.5f), 0.0f, std::cos(yaw * 0.5f)};
    cursorVisible_ = true;
}

void XrApp::applyInput(XrTime time) {
    if (!actionsAttached_ || !sessionRunning_) return;

    XrActiveActionSet active{actionSet_, XR_NULL_PATH};
    XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &active;
    if (XR_FAILED(xrSyncActions(session_, &sync))) return;

    XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};

    get.action = recenterAction_;
    XrActionStateBoolean recenterState{XR_TYPE_ACTION_STATE_BOOLEAN};
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &get, &recenterState)) &&
        recenterState.isActive && recenterState.currentState &&
        recenterState.changedSinceLastSync) {
        recenter(time);
    }

    get.action = gripAction_;
    XrActionStateFloat gripState{XR_TYPE_ACTION_STATE_FLOAT};
    bool gripHeld = false;
    if (XR_SUCCEEDED(xrGetActionStateFloat(session_, &get, &gripState)) && gripState.isActive) {
        // Hysteresis-free threshold is fine here: a half-squeezed grip that
        // flickers would be more annoying than one that needs a firm press.
        gripHeld = gripState.currentState > 0.7f;
    }
    updateDrag(time, gripHeld);

    get.action = resetAction_;
    XrActionStateBoolean resetState{XR_TYPE_ACTION_STATE_BOOLEAN};
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &get, &resetState)) &&
        resetState.isActive && resetState.currentState && resetState.changedSinceLastSync) {
        geometry.radius = 1.5f;
        geometry.horizontalFovDegrees = 66.0f;
        LOGI("geometry reset: distance %.2f m, width %.0f deg", geometry.radius,
             geometry.horizontalFovDegrees);
    }

    get.action = thumbstickAction_;
    XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
    if (XR_FAILED(xrGetActionStateVector2f(session_, &get, &stick)) || !stick.isActive) return;

    bool changed = false;

    // Forward/back — distance
    if (stick.currentState.y > kStickDeadzone || stick.currentState.y < -kStickDeadzone) {
        geometry.radius += stick.currentState.y * 0.01f;
        if (geometry.radius < kMinRadius) geometry.radius = kMinRadius;
        if (geometry.radius > kMaxRadius) geometry.radius = kMaxRadius;
        changed = true;
    }

    // Left/right — angular width
    if (stick.currentState.x > kStickDeadzone || stick.currentState.x < -kStickDeadzone) {
        geometry.horizontalFovDegrees += stick.currentState.x * 0.3f;
        if (geometry.horizontalFovDegrees < kMinFovDeg) geometry.horizontalFovDegrees = kMinFovDeg;
        if (geometry.horizontalFovDegrees > kMaxFovDeg) geometry.horizontalFovDegrees = kMaxFovDeg;
        changed = true;
    }

    // Throttled: logging every frame turns logcat into mush
    static int throttle = 0;
    if (changed && (++throttle % 30) == 0) {
        const float pxPerDegree = static_cast<float>(videoWidth_) / geometry.horizontalFovDegrees;
        LOGI("distance %.2f m, width %.0f deg, %.1f px/deg", geometry.radius,
             geometry.horizontalFovDegrees, pxPerDegree);
    }
}

void XrApp::handleStateChange(const XrEventDataSessionStateChanged &ev) {
    state_ = ev.state;
    LOGI("session state -> %d", static_cast<int>(state_));

    switch (state_) {
        case XR_SESSION_STATE_READY: {
            XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
            begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            if (XR_CHECK(xrBeginSession(session_, &begin))) sessionRunning_ = true;
            break;
        }
        case XR_SESSION_STATE_STOPPING:
            sessionRunning_ = false;
            XR_CHECK(xrEndSession(session_));
            break;
        case XR_SESSION_STATE_EXITING:
        case XR_SESSION_STATE_LOSS_PENDING:
            exitRequested_ = true;
            break;
        default:
            break;
    }
}

bool XrApp::pollEvents(android_app *app) {
    XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
    while (true) {
        ev = {XR_TYPE_EVENT_DATA_BUFFER};
        const XrResult r = xrPollEvent(instance_, &ev);
        if (r == XR_EVENT_UNAVAILABLE) break;
        if (XR_FAILED(r)) break;

        if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            handleStateChange(*reinterpret_cast<XrEventDataSessionStateChanged *>(&ev));
        } else if (ev.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
            exitRequested_ = true;
        }
    }
    return !exitRequested_ && !app->destroyRequested;
}

void XrApp::renderFrame() {
    if (!sessionRunning_) return;

    XrFrameWaitInfo waitInfo{XR_TYPE_FRAME_WAIT_INFO};
    XrFrameState frameState{XR_TYPE_FRAME_STATE};
    if (!XR_CHECK(xrWaitFrame(session_, &waitInfo, &frameState))) return;

    XrFrameBeginInfo beginInfo{XR_TYPE_FRAME_BEGIN_INFO};
    if (!XR_CHECK(xrBeginFrame(session_, &beginInfo))) return;

    applyInput(frameState.predictedDisplayTime);

    std::vector<XrCompositionLayerBaseHeader *> layers;
    XrCompositionLayerCylinderKHR cylinder{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
    XrCompositionLayerImageLayoutFB imageLayout{XR_TYPE_COMPOSITION_LAYER_IMAGE_LAYOUT_FB};

    if (frameState.shouldRender && videoSwapchain_ != XR_NULL_HANDLE) {
        // Vertical flip: compensates for the difference in origin between an
        // Android Surface and an OpenXR swapchain
        imageLayout.flags = XR_COMPOSITION_LAYER_IMAGE_LAYOUT_VERTICAL_FLIP_BIT_FB;
        cylinder.next = &imageLayout;
        cylinder.layerFlags = 0;
        cylinder.space = space_;
        cylinder.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
        cylinder.subImage.swapchain = videoSwapchain_;
        cylinder.subImage.imageRect.offset = {0, 0};
        cylinder.subImage.imageRect.extent = {static_cast<int32_t>(videoWidth_),
                                              static_cast<int32_t>(videoHeight_)};
        cylinder.subImage.imageArrayIndex = 0;

        // The cylinder axis passes through the pose along its +Y. The visible
        // surface ends up at distance `radius` along -Z, so rotating the pose
        // about Y is what moves the screen around the user.
        cylinder.pose.orientation = {0.0f, std::sin(geometry.yaw * 0.5f), 0.0f,
                                     std::cos(geometry.yaw * 0.5f)};
        cylinder.pose.position = {0.0f, geometry.heightOffset, 0.0f};
        cylinder.radius = geometry.radius;
        cylinder.centralAngle = geometry.horizontalFovDegrees * kPi / 180.0f;
        cylinder.aspectRatio = geometry.aspectRatio;

        layers.push_back(reinterpret_cast<XrCompositionLayerBaseHeader *>(&cylinder));
    }

    // The cursor goes second, on top of the desktop.
    // Array order is composition order.
    XrCompositionLayerQuad cursor{XR_TYPE_COMPOSITION_LAYER_QUAD};
    if (frameState.shouldRender && cursorSwapchain_ != XR_NULL_HANDLE) {
        updateCursor(frameState.predictedDisplayTime);
        if (cursorVisible_) {
            drawCursorImage();

            cursor.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
            cursor.space = space_;
            cursor.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
            cursor.subImage.swapchain = cursorSwapchain_;
            cursor.subImage.imageRect.offset = {0, 0};
            cursor.subImage.imageRect.extent = {static_cast<int32_t>(kCursorSize),
                                                static_cast<int32_t>(kCursorSize)};
            cursor.subImage.imageArrayIndex = 0;
            cursor.pose = cursorPose_;
            cursor.size = {kCursorMeters, kCursorMeters};

            layers.push_back(reinterpret_cast<XrCompositionLayerBaseHeader *>(&cursor));

            // Fractional coordinates are what will travel to the host as the
            // mouse position. Logged once a second for now, to confirm the
            // arithmetic.
            static int tick = 0;
            if ((++tick % 90) == 0) {
                LOGI("cursor: %.3f x %.3f  ->  pixels %d, %d", cursorU_, cursorV_,
                     static_cast<int>(cursorU_ * videoWidth_),
                     static_cast<int>(cursorV_ * videoHeight_));
            }
        }
    }

    XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
    endInfo.displayTime = frameState.predictedDisplayTime;
    // OPAQUE: we draw on black for now. Blending with passthrough is a separate
    // step, via XR_FB_composition_layer_alpha_blend.
    endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    endInfo.layerCount = static_cast<uint32_t>(layers.size());
    endInfo.layers = layers.data();
    XR_CHECK(xrEndFrame(session_, &endInfo));
}

void XrApp::shutdown() {
    if (videoWindow_ != nullptr) {
        ANativeWindow_release(videoWindow_);
        videoWindow_ = nullptr;
    }
    if (cursorSwapchain_ != XR_NULL_HANDLE) xrDestroySwapchain(cursorSwapchain_);
    if (videoSwapchain_ != XR_NULL_HANDLE) xrDestroySwapchain(videoSwapchain_);
    if (aimSpace_ != XR_NULL_HANDLE) xrDestroySpace(aimSpace_);
    if (viewSpace_ != XR_NULL_HANDLE) xrDestroySpace(viewSpace_);
    if (space_ != XR_NULL_HANDLE) xrDestroySpace(space_);
    if (actionSet_ != XR_NULL_HANDLE) xrDestroyActionSet(actionSet_);
    if (session_ != XR_NULL_HANDLE) xrDestroySession(session_);
    if (instance_ != XR_NULL_HANDLE) xrDestroyInstance(instance_);
    egl_.destroy();
}
