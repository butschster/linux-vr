#pragma once

#include <android/native_window.h>

#include <atomic>
#include <string>
#include <thread>

// Decodes a file into an ANativeWindow.
//
// Milestone B: frames come from a local file rather than the network, which
// lets the whole MediaCodec -> surface swapchain -> cylinder layer path be
// verified without a transport or a virtual display.
//
// In milestone C this class is replaced by a live receiver with the same
// output: frames go into the same ANativeWindow and nothing else changes.
class VideoDecoder {
public:
    ~VideoDecoder();

    // window — obtained from the Surface of a surface swapchain. The decoder
    // writes frames straight into it; there is no copy through system memory.
    bool start(const std::string &path, ANativeWindow *window);
    void stop();

    // Diagnostics: how many frames actually reached the compositor
    uint64_t framesRendered() const { return framesRendered_.load(); }

private:
    void threadMain();

    std::string path_;
    ANativeWindow *window_ = nullptr;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<uint64_t> framesRendered_{0};
};
