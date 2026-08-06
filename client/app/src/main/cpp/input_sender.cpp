#include "input_sender.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cmath>
#include <cstdio>
#include <cstring>

#include "log.h"

namespace {

// Below this the move is not worth a packet: at 2560 px across the layer it is
// well under one pixel, and the host would round it away anyway.
constexpr float kMoveEpsilon = 0.0002f;

}  // namespace

InputSender::~InputSender() {
    disconnect();
}

bool InputSender::connect(const std::string &host, int port) {
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    const std::string portStr = std::to_string(port);
    addrinfo *result = nullptr;
    if (getaddrinfo(host.c_str(), portStr.c_str(), &hints, &result) != 0 || result == nullptr) {
        LOGE("input: cannot resolve %s", host.c_str());
        return false;
    }

    int fd = -1;
    for (addrinfo *ai = result; ai != nullptr; ai = ai->ai_next) {
        fd = ::socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (fd < 0) continue;
        if (::connect(fd, ai->ai_addr, ai->ai_addrlen) == 0) break;
        ::close(fd);
        fd = -1;
    }
    freeaddrinfo(result);

    if (fd < 0) {
        LOGW("input: no agent at %s:%d — pointer will be local only", host.c_str(), port);
        return false;
    }

    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    // Non-blocking on purpose: a stalled agent must not stall the frame loop.
    const int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);

    socket_ = fd;
    LOGI("input: connected to agent %s:%d", host.c_str(), port);
    return true;
}

void InputSender::disconnect() {
    if (socket_ >= 0) {
        ::close(socket_);
        socket_ = -1;
        LOGI("input: %llu commands sent, %llu dropped",
             static_cast<unsigned long long>(sent_.load()),
             static_cast<unsigned long long>(dropped_.load()));
    }
}

void InputSender::sendLine(const char *line, size_t length) {
    if (socket_ < 0) return;
    const ssize_t n = ::send(socket_, line, length, MSG_NOSIGNAL);
    if (n < 0) {
        // EAGAIN means the buffer is full — the next position replaces this one,
        // so dropping is the right answer for a pointer.
        dropped_.fetch_add(1);
        return;
    }
    sent_.fetch_add(1);
}

void InputSender::sendMove(float u, float v) {
    if (socket_ < 0) return;
    if (std::fabs(u - lastU_) < kMoveEpsilon && std::fabs(v - lastV_) < kMoveEpsilon) return;
    lastU_ = u;
    lastV_ = v;

    char line[48];
    const int n = std::snprintf(line, sizeof(line), "m %.5f %.5f\n", u, v);
    if (n > 0) sendLine(line, static_cast<size_t>(n));
}

void InputSender::sendButton(const char *button, bool pressed) {
    if (socket_ < 0) return;

    char line[32];
    const int n = std::snprintf(line, sizeof(line), "%c %s\n", pressed ? 'd' : 'u', button);
    if (n > 0) sendLine(line, static_cast<size_t>(n));
}
