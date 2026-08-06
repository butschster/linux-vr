#pragma once

#include <atomic>
#include <string>

// Sends pointer input to the host agent (host/input-agent.py).
//
// The socket is non-blocking and a full send buffer drops the update rather
// than waiting. For a pointer that is correct: the next position supersedes the
// one that did not fit, and blocking here would stall the render thread.
//
// Coordinates are fractions of the layer, not pixels: the headset does not know
// the desktop resolution, and the virtual pointer on the host works in an
// abstract absolute range that libinput maps to the real screen.
class InputSender {
public:
    ~InputSender();

    bool connect(const std::string &host, int port);
    void disconnect();
    bool connected() const { return socket_ >= 0; }

    void sendMove(float u, float v);
    void sendButton(const char *button, bool pressed);

private:
    void sendLine(const char *line, size_t length);

    int socket_ = -1;
    float lastU_ = -1.0f;
    float lastV_ = -1.0f;
    std::atomic<uint64_t> sent_{0};
    std::atomic<uint64_t> dropped_{0};
};
