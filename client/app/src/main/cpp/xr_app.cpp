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

// Расширения, без которых приложение не имеет смысла.
// Наличие каждого подтверждено пробой вехи A на Quest 3 (см. docs/device-probe.md).
const char *kRequiredExtensions[] = {
    "XR_KHR_android_create_instance",
    "XR_KHR_opengl_es_enable",
    "XR_KHR_android_surface_swapchain",
    "XR_KHR_composition_layer_cylinder",
    // MediaCodec пишет в Surface с началом координат в левом верхнем углу,
    // swapchain OpenXR ждёт противоположного. Без этого расширения картинка
    // приезжает перевёрнутой. Meta сделала его ровно под этот случай.
    "XR_FB_composition_layer_image_layout",
};

// Границы живой подстройки геометрии стиком
constexpr float kMinRadius = 0.8f;
constexpr float kMaxRadius = 3.0f;
constexpr float kMinFovDeg = 25.0f;
constexpr float kMaxFovDeg = 110.0f;
constexpr float kStickDeadzone = 0.25f;

// Курсор: 64x64 хватает с запасом, слой всё равно занимает пару сантиметров
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
    // Требования к версии GL обязательно запросить до создания сессии,
    // иначе рантайм имеет право отказать.
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

    // LOCAL, а не STAGE: монитор должен стоять там, где сидит человек,
    // и переезжать вместе с ним при перецентровке.
    XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    return XR_CHECK(xrCreateReferenceSpace(session_, &spaceInfo, &space_));
}

bool XrApp::init(android_app *app) {
    vm_ = app->activity->vm;
    if (!initLoader(app)) return false;

    // Диагностика идёт сразу после loader'а: если чего-то критичного нет,
    // это должно быть видно в логе до первой попытки что-либо создать.
    runCapabilityProbe(app);

    if (!egl_.create()) return false;
    if (!createInstance(app)) return false;
    if (!createSession()) return false;
    if (!createActions()) return false;
    if (!createCursor()) return false;

    LOGI("сессия OpenXR создана");
    return true;
}

ANativeWindow *XrApp::createVideoSurface(uint32_t width, uint32_t height) {
    if (xrCreateSwapchainAndroidSurfaceKHR_ == nullptr) return nullptr;

    // Для surface swapchain заполняются только ширина и высота.
    // Формат, sampleCount, faceCount, arraySize и mipCount обязаны быть нулями:
    // содержимым распоряжается производитель кадров, то есть MediaCodec,
    // а рантайм отвергает создание с ненулевыми значениями (XR_ERROR_VALIDATION_FAILURE).
    XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    ci.width = width;
    ci.height = height;

    jobject surface = nullptr;
    if (!XR_CHECK(xrCreateSwapchainAndroidSurfaceKHR_(session_, &ci, &videoSwapchain_, &surface))) {
        return nullptr;
    }

    // Surface приходит как jobject — нужен JNIEnv текущего потока.
    // Поток android_main создан native_app_glue и к JVM не привязан,
    // поэтому GetEnv здесь штатно возвращает JNI_EDETACHED.
    JNIEnv *env = nullptr;
    if (vm_ == nullptr) {
        LOGE("JavaVM не сохранён");
        return nullptr;
    }
    if (vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("AttachCurrentThread провалился");
            return nullptr;
        }
    }

    videoWindow_ = ANativeWindow_fromSurface(env, surface);
    if (videoWindow_ == nullptr) {
        LOGE("ANativeWindow_fromSurface вернул null");
        return nullptr;
    }

    videoWidth_ = width;
    videoHeight_ = height;
    geometry.aspectRatio = static_cast<float>(width) / static_cast<float>(height);

    LOGI("surface swapchain создан: %ux%u", width, height);
    return videoWindow_;
}

// Ввод с контроллера. Пока служит одной цели — подобрать геометрию слоя
// живьём, в шлеме, вместо угадывания константами и пересборок.
// Тот же action set станет основой push-to-talk для голосового управления.
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

    // aim, а не grip: aim — это луч указания, ориентированный так, как
    // пользователь ожидает от «лазерной указки», а grip привязан к хвату.
    XrActionCreateInfo aimInfo{XR_TYPE_ACTION_CREATE_INFO};
    std::strcpy(aimInfo.actionName, "aim");
    std::strcpy(aimInfo.localizedActionName, "Pointer ray");
    aimInfo.actionType = XR_ACTION_TYPE_POSE_INPUT;
    if (!XR_CHECK(xrCreateAction(actionSet_, &aimInfo, &aimAction_))) return false;

    XrPath stickPath = XR_NULL_PATH;
    XrPath aPath = XR_NULL_PATH;
    XrPath aimPath = XR_NULL_PATH;
    XrPath profilePath = XR_NULL_PATH;
    xrStringToPath(instance_, "/user/hand/right/input/thumbstick", &stickPath);
    xrStringToPath(instance_, "/user/hand/right/input/a/click", &aPath);
    xrStringToPath(instance_, "/user/hand/right/input/aim/pose", &aimPath);
    xrStringToPath(instance_, "/interaction_profiles/oculus/touch_controller", &profilePath);

    const XrActionSuggestedBinding bindings[] = {
        {thumbstickAction_, stickPath},
        {resetAction_, aPath},
        {aimAction_, aimPath},
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

    // Пространство луча создаётся после привязки набора действий
    XrActionSpaceCreateInfo spaceInfo{XR_TYPE_ACTION_SPACE_CREATE_INFO};
    spaceInfo.action = aimAction_;
    spaceInfo.poseInActionSpace.orientation.w = 1.0f;
    if (!XR_CHECK(xrCreateActionSpace(session_, &spaceInfo, &aimSpace_))) return false;

    actionsAttached_ = true;
    LOGI("ввод подключён: стик — дистанция и ширина, A — сброс, луч — указатель");
    return true;
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
    LOGI("слой курсора создан: %ux%u, буферов %u", kCursorSize, kCursorSize, count);
    return true;
}

// Рисуем перекрестие ножницами и очисткой — без шейдеров и без геометрии.
// Для курсора этого достаточно, а кода на порядок меньше.
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

    // Чёрная подложка под перекрестием — иначе белый курсор теряется
    // на светлом фоне рабочего стола
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

    // Направление луча — вектор -Z, повёрнутый ориентацией контроллера
    const float dx = -2.0f * (q.x * q.z + q.w * q.y);
    const float dy = -2.0f * (q.y * q.z - q.w * q.x);
    const float dz = -(1.0f - 2.0f * (q.x * q.x + q.y * q.y));

    // Пересечение с бесконечным цилиндром радиуса R вокруг оси Y.
    // Наблюдатель внутри, поэтому нужен дальний корень.
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

    // Угол попадания отсчитывается от -Z, куда смотрит центр слоя
    const float angle = std::atan2(hx, -hz);
    const float halfAngle = geometry.horizontalFovDegrees * kPi / 180.0f * 0.5f;
    if (angle < -halfAngle || angle > halfAngle) return;

    // Высота слоя в метрах выводится из длины дуги и соотношения сторон
    const float arc = R * (2.0f * halfAngle);
    const float heightMeters = arc / geometry.aspectRatio;
    const float dyFromCenter = hy - geometry.heightOffset;
    if (dyFromCenter < -heightMeters * 0.5f || dyFromCenter > heightMeters * 0.5f) return;

    // Доли от 0 до 1 — будущие координаты мыши на десктопе
    cursorU_ = 0.5f + angle / (2.0f * halfAngle);
    cursorV_ = 0.5f - dyFromCenter / heightMeters;

    // Курсор чуть ближе поверхности, иначе компоситор может дать z-fighting
    const float lift = 0.01f;
    const float scale = (R - lift) / R;
    cursorPose_.position = {hx * scale, hy, hz * scale};

    // Разворачиваем плоскость курсора лицом к центру цилиндра
    const float yaw = -angle;
    cursorPose_.orientation = {0.0f, std::sin(yaw * 0.5f), 0.0f, std::cos(yaw * 0.5f)};
    cursorVisible_ = true;
}

void XrApp::applyInput() {
    if (!actionsAttached_ || !sessionRunning_) return;

    XrActiveActionSet active{actionSet_, XR_NULL_PATH};
    XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &active;
    if (XR_FAILED(xrSyncActions(session_, &sync))) return;

    XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};

    get.action = resetAction_;
    XrActionStateBoolean resetState{XR_TYPE_ACTION_STATE_BOOLEAN};
    if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &get, &resetState)) &&
        resetState.isActive && resetState.currentState && resetState.changedSinceLastSync) {
        geometry.radius = 1.5f;
        geometry.horizontalFovDegrees = 66.0f;
        LOGI("геометрия сброшена: дистанция %.2f м, ширина %.0f°", geometry.radius,
             geometry.horizontalFovDegrees);
    }

    get.action = thumbstickAction_;
    XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
    if (XR_FAILED(xrGetActionStateVector2f(session_, &get, &stick)) || !stick.isActive) return;

    bool changed = false;

    // Вперёд-назад — дистанция
    if (stick.currentState.y > kStickDeadzone || stick.currentState.y < -kStickDeadzone) {
        geometry.radius += stick.currentState.y * 0.01f;
        if (geometry.radius < kMinRadius) geometry.radius = kMinRadius;
        if (geometry.radius > kMaxRadius) geometry.radius = kMaxRadius;
        changed = true;
    }

    // Влево-вправо — угловая ширина
    if (stick.currentState.x > kStickDeadzone || stick.currentState.x < -kStickDeadzone) {
        geometry.horizontalFovDegrees += stick.currentState.x * 0.3f;
        if (geometry.horizontalFovDegrees < kMinFovDeg) geometry.horizontalFovDegrees = kMinFovDeg;
        if (geometry.horizontalFovDegrees > kMaxFovDeg) geometry.horizontalFovDegrees = kMaxFovDeg;
        changed = true;
    }

    // Логируем не каждый кадр, иначе logcat превращается в кашу
    static int throttle = 0;
    if (changed && (++throttle % 30) == 0) {
        const float pxPerDegree = static_cast<float>(videoWidth_) / geometry.horizontalFovDegrees;
        LOGI("дистанция %.2f м, ширина %.0f°, %.1f пикс/градус", geometry.radius,
             geometry.horizontalFovDegrees, pxPerDegree);
    }
}

void XrApp::handleStateChange(const XrEventDataSessionStateChanged &ev) {
    state_ = ev.state;
    LOGI("состояние сессии -> %d", static_cast<int>(state_));

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

    applyInput();

    std::vector<XrCompositionLayerBaseHeader *> layers;
    XrCompositionLayerCylinderKHR cylinder{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
    XrCompositionLayerImageLayoutFB imageLayout{XR_TYPE_COMPOSITION_LAYER_IMAGE_LAYOUT_FB};

    if (frameState.shouldRender && videoSwapchain_ != XR_NULL_HANDLE) {
        // Разворот по вертикали: компенсация разницы в началах координат
        // между Android Surface и swapchain OpenXR
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

        // Ось цилиндра проходит через pose и направлена по его +Y.
        // Видимая поверхность оказывается на расстоянии radius по -Z.
        cylinder.pose.orientation = {0.0f, 0.0f, 0.0f, 1.0f};
        cylinder.pose.position = {0.0f, geometry.heightOffset, 0.0f};
        cylinder.radius = geometry.radius;
        cylinder.centralAngle = geometry.horizontalFovDegrees * kPi / 180.0f;
        cylinder.aspectRatio = geometry.aspectRatio;

        layers.push_back(reinterpret_cast<XrCompositionLayerBaseHeader *>(&cylinder));
    }

    // Курсор — вторым слоем, поверх десктопа.
    // Порядок в массиве и есть порядок наложения.
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

            // Координаты в долях — то, что уедет на хост как позиция мыши.
            // Пока только в лог, раз в секунду, чтобы видеть, что счёт верный.
            static int tick = 0;
            if ((++tick % 90) == 0) {
                LOGI("курсор: %.3f x %.3f  ->  пиксели %d, %d", cursorU_, cursorV_,
                     static_cast<int>(cursorU_ * videoWidth_),
                     static_cast<int>(cursorV_ * videoHeight_));
            }
        }
    }

    XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
    endInfo.displayTime = frameState.predictedDisplayTime;
    // OPAQUE: пока рисуем на чёрном фоне. Смешивание с passthrough —
    // отдельный шаг, через XR_FB_composition_layer_alpha_blend.
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
    if (videoSwapchain_ != XR_NULL_HANDLE) xrDestroySwapchain(videoSwapchain_);
    if (space_ != XR_NULL_HANDLE) xrDestroySpace(space_);
    if (session_ != XR_NULL_HANDLE) xrDestroySession(session_);
    if (instance_ != XR_NULL_HANDLE) xrDestroyInstance(instance_);
    egl_.destroy();
}
