#pragma once

#include <android/native_window.h>

#include <atomic>
#include <string>
#include <thread>

// Receives live H.264 over TCP and decodes straight into the compositor
// surface.
//
// The difference from VideoDecoder is not only the source. A file is played
// back according to presentation time; a live stream is displayed as soon as
// possible — the frame goes to the compositor right after decoding, with no
// pacing. Here latency costs more than smoothness, and smoothness is provided
// by layer reprojection anyway.
class StreamDecoder {
public:
    ~StreamDecoder();

    bool start(const std::string &host, int port, ANativeWindow *window);
    void stop();

    uint64_t framesRendered() const { return framesRendered_.load(); }
    uint64_t bytesReceived() const { return bytesReceived_.load(); }
    bool connected() const { return connected_.load(); }

private:
    void receiveThread();
    void drainThread();
    int connectToHost();

    std::string host_;
    int port_ = 0;
    int socket_ = -1;
    ANativeWindow *window_ = nullptr;

    // AMediaCodec* hidden behind void* to keep NDK headers out of the interface
    void *codec_ = nullptr;

    std::thread receiver_;
    std::thread drainer_;
    std::atomic<bool> running_{false};
    std::atomic<bool> connected_{false};
    std::atomic<uint64_t> framesRendered_{0};
    std::atomic<uint64_t> bytesReceived_{0};
};
