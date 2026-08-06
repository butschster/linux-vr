#pragma once

#include <EGL/egl.h>

// Минимальный EGL-контекст.
//
// Своей геометрии мы не рисуем — вся картинка живёт в слое компоситора.
// Но OpenXR требует графическую привязку для создания сессии, поэтому контекст
// нужен. Отсюда pbuffer 16x16: он никогда не используется, просто должен
// существовать, чтобы eglMakeCurrent отработал.
class EglContext {
public:
    bool create();
    void destroy();

    EGLDisplay display() const { return display_; }
    EGLConfig config() const { return config_; }
    EGLContext context() const { return context_; }

private:
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLSurface surface_ = EGL_NO_SURFACE;
    EGLContext context_ = EGL_NO_CONTEXT;
};
