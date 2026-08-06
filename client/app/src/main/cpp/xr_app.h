#pragma once

#include <android/native_window.h>
#include <jni.h>

#include <string>
#include <vector>

// Порядок важен: openxr_platform.h объявляет структуры с полями EGLDisplay,
// EGLConfig и EGLContext, но сам заголовки EGL не подтягивает.
// Включить его раньше EGL — гарантированная стена ошибок «unknown type name».
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include "egl_context.h"

struct android_app;

// Геометрия виртуального монитора.
//
// Цилиндр, а не плоскость: у широкого лейаута меньше искажений по краям
// и постоянная дистанция аккомодации — глазу не приходится перефокусироваться
// при переводе взгляда от центра к краю.
struct LayerGeometry {
    // Дистанция до поверхности, м. 1.2-1.8 — рабочий диапазон:
    // дальше теряется угловое разрешение, ближе начинается
    // вергентно-аккомодационный конфликт при долгой работе.
    float radius = 1.5f;

    // Угловая ширина по горизонтали, градусы. 45-60 — рабочий диапазон.
    float horizontalFovDegrees = 50.0f;

    // Отношение сторон источника
    float aspectRatio = 16.0f / 9.0f;

    // Смещение центра относительно точки отсчёта, м
    float heightOffset = 0.0f;
};

// Сессия OpenXR со слоем-монитором.
//
// Своей геометрии не рисует вообще: сцена пустая, объектов ноль.
// Кадр приходит в surface swapchain от декодера и уходит в компоситор
// цилиндрическим слоем, минуя eye buffer и foveated rendering.
class XrApp {
public:
    bool init(android_app *app);
    void shutdown();

    // Создаёт swapchain, за которым стоит Android Surface.
    // Возвращённое окно передаётся декодеру — дальше кадры идут в компоситор
    // без единой копии через системную память.
    ANativeWindow *createVideoSurface(uint32_t width, uint32_t height);

    // Обрабатывает события рантайма. false — пора завершаться.
    bool pollEvents(android_app *app);

    // Один кадр компоситора. Вызывать в цикле.
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
    bool sessionRunning_ = false;
    bool exitRequested_ = false;

    XrSwapchain videoSwapchain_ = XR_NULL_HANDLE;
    uint32_t videoWidth_ = 0;
    uint32_t videoHeight_ = 0;
    ANativeWindow *videoWindow_ = nullptr;

    // Ввод: пока только подстройка геометрии стиком.
    // Тот же механизм понадобится под push-to-talk для голосового управления.
    XrActionSet actionSet_ = XR_NULL_HANDLE;
    XrAction thumbstickAction_ = XR_NULL_HANDLE;
    XrAction resetAction_ = XR_NULL_HANDLE;
    XrAction aimAction_ = XR_NULL_HANDLE;
    XrSpace aimSpace_ = XR_NULL_HANDLE;
    bool actionsAttached_ = false;

    // Курсор живёт отдельным слоем, а не рисуется в картинку десктопа.
    // Компоситор обновляет его на частоте гарнитуры, поэтому указатель
    // остаётся мгновенным независимо от задержек стрима.
    XrSwapchain cursorSwapchain_ = XR_NULL_HANDLE;
    std::vector<XrSwapchainImageOpenGLESKHR> cursorImages_;
    unsigned int cursorFbo_ = 0;
    bool cursorVisible_ = false;
    XrPosef cursorPose_{};
    float cursorU_ = 0.0f;
    float cursorV_ = 0.0f;

    bool createActions();
    bool createCursor();
    void applyInput();
    // Пересечение луча контроллера с цилиндром. Возвращает координаты
    // попадания в долях от 0 до 1 — это и есть позиция мыши на десктопе.
    void updateCursor(XrTime time);
    void drawCursorImage();

    PFN_xrCreateSwapchainAndroidSurfaceKHR xrCreateSwapchainAndroidSurfaceKHR_ = nullptr;
};
