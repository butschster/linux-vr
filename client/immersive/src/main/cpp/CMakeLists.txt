cmake_minimum_required(VERSION 3.22.1)
project(linuxvr LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The Khronos loader arrives as a prefab package from the AAR
find_package(OpenXR REQUIRED CONFIG)

# native_app_glue ships with the NDK and is built alongside the application
add_library(native_app_glue STATIC
    ${ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c)
target_include_directories(native_app_glue PUBLIC
    ${ANDROID_NDK}/sources/android/native_app_glue)

add_library(linuxvr SHARED
    main.cpp
    probe.cpp
    egl_context.cpp
    video_decoder.cpp
    stream_decoder.cpp
    input_sender.cpp
    xr_app.cpp)

target_link_libraries(linuxvr
    PRIVATE
        native_app_glue
        OpenXR::openxr_loader
        android
        log
        mediandk      # AMediaCodec / AMediaExtractor
        EGL
        GLESv3)

# Without this the linker drops ANativeActivity_onCreate as unused
target_link_options(linuxvr PRIVATE "-u" "ANativeActivity_onCreate")
