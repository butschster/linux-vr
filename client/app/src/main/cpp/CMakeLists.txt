cmake_minimum_required(VERSION 3.22.1)
project(linuxvr LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Loader от Khronos приезжает через prefab из AAR
find_package(OpenXR REQUIRED CONFIG)

# native_app_glue лежит в NDK, собирается вместе с приложением
add_library(native_app_glue STATIC
    ${ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c)
target_include_directories(native_app_glue PUBLIC
    ${ANDROID_NDK}/sources/android/native_app_glue)

add_library(linuxvr SHARED
    main.cpp
    probe.cpp
    egl_context.cpp
    video_decoder.cpp
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

# Без этого ANativeActivity_onCreate выкидывается линкером как неиспользуемый
target_link_options(linuxvr PRIVATE "-u" "ANativeActivity_onCreate")
