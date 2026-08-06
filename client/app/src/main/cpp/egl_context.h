#pragma once

#include <EGL/egl.h>

// Minimal EGL context.
//
// We render no geometry of our own — the entire picture lives in a compositor
// layer. But OpenXR requires a graphics binding to create a session, so a
// context is needed anyway. Hence the 16x16 pbuffer: it is never used, it just
// has to exist so that eglMakeCurrent succeeds.
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
