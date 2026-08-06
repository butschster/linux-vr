#include "stream_decoder.h"

#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <cstring>
#include <vector>

#include "log.h"

namespace {

constexpr int64_t kDequeueTimeoutUs = 10000;
constexpr size_t kReadChunk = 64 * 1024;

// The stream is raw Annex-B: NAL units separated by 00 00 01 or 00 00 00 01.
// Returns the offset of the next start code at or after `from`, or npos.
size_t findStartCode(const std::vector<uint8_t> &buf, size_t from, size_t *codeLen) {
    for (size_t i = from; i + 3 <= buf.size(); ++i) {
        if (buf[i] == 0 && buf[i + 1] == 0) {
            if (buf[i + 2] == 1) {
                *codeLen = 3;
                return i;
            }
            if (i + 4 <= buf.size() && buf[i + 2] == 0 && buf[i + 3] == 1) {
                *codeLen = 4;
                return i;
            }
        }
    }
    return std::string::npos;
}

// A VCL NAL (types 1 and 5) carries picture data and, with one slice per
// frame, ends the access unit. Everything before it — SPS, PPS, SEI — belongs
// to the same access unit and must be submitted together with it.
bool isVclNal(uint8_t header) {
    const uint8_t type = header & 0x1F;
    return type == 1 || type == 5;
}

}  // namespace

StreamDecoder::~StreamDecoder() {
    stop();
}

int StreamDecoder::connectToHost() {
    addrinfo hints{};
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;

    const std::string portStr = std::to_string(port_);
    addrinfo *result = nullptr;
    if (getaddrinfo(host_.c_str(), portStr.c_str(), &hints, &result) != 0 || result == nullptr) {
        LOGE("cannot resolve %s", host_.c_str());
        return -1;
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
        LOGE("cannot connect to %s:%d", host_.c_str(), port_);
        return -1;
    }

    // Nagle batches small writes, which is exactly the wrong trade here:
    // a few milliseconds of added latency to save a few bytes.
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    LOGI("connected to %s:%d", host_.c_str(), port_);
    return fd;
}

bool StreamDecoder::start(const std::string &host, int port, ANativeWindow *window) {
    if (running_.load()) return false;
    if (window == nullptr) {
        LOGE("stream decoder got no output window");
        return false;
    }

    host_ = host;
    port_ = port;
    window_ = window;
    running_.store(true);

    receiver_ = std::thread(&StreamDecoder::receiveThread, this);
    return true;
}

void StreamDecoder::stop() {
    running_.store(false);

    if (socket_ >= 0) {
        ::shutdown(socket_, SHUT_RDWR);
    }
    if (receiver_.joinable()) receiver_.join();
    if (drainer_.joinable()) drainer_.join();
    if (socket_ >= 0) {
        ::close(socket_);
        socket_ = -1;
    }
}

// Output side: hand every decoded frame to the compositor immediately.
// No pacing by presentation time — unlike file playback, here latency costs
// more than smoothness, and smoothness comes from layer reprojection anyway.
void StreamDecoder::drainThread() {
    auto *codec = static_cast<AMediaCodec *>(codec_);

    while (running_.load()) {
        AMediaCodecBufferInfo info;
        const ssize_t idx = AMediaCodec_dequeueOutputBuffer(codec, &info, kDequeueTimeoutUs);
        if (idx >= 0) {
            AMediaCodec_releaseOutputBuffer(codec, idx, true);
            framesRendered_.fetch_add(1);
        } else if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *fmt = AMediaCodec_getOutputFormat(codec);
            LOGI("output format: %s", AMediaFormat_toString(fmt));
            AMediaFormat_delete(fmt);
        }
    }
}

void StreamDecoder::receiveThread() {
    socket_ = connectToHost();
    if (socket_ < 0) {
        running_.store(false);
        return;
    }
    connected_.store(true);

    // Width and height are advisory here: the decoder takes the real geometry
    // from the SPS in the stream. They only have to be non-zero for configure.
    AMediaFormat *format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, 2560);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, 1440);
    // Ask for the lowest-latency mode the decoder offers. Supported since
    // API 30; on older ones the key is simply ignored.
    AMediaFormat_setInt32(format, "low-latency", 1);

    AMediaCodec *codec = AMediaCodec_createDecoderByType("video/avc");
    if (codec == nullptr) {
        LOGE("no hardware H.264 decoder");
        AMediaFormat_delete(format);
        running_.store(false);
        return;
    }

    if (AMediaCodec_configure(codec, format, window_, nullptr, 0) != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed");
        AMediaCodec_delete(codec);
        AMediaFormat_delete(format);
        running_.store(false);
        return;
    }
    AMediaCodec_start(codec);
    codec_ = codec;

    drainer_ = std::thread(&StreamDecoder::drainThread, this);

    std::vector<uint8_t> pending;   // received but not yet split into NAL units
    std::vector<uint8_t> accessUnit;
    std::vector<uint8_t> chunk(kReadChunk);
    int64_t ptsUs = 0;
    const int64_t frameDurationUs = 1000000 / 90;

    while (running_.load()) {
        const ssize_t n = ::recv(socket_, chunk.data(), chunk.size(), 0);
        if (n <= 0) {
            LOGE("connection closed by host");
            break;
        }
        bytesReceived_.fetch_add(static_cast<uint64_t>(n));
        pending.insert(pending.end(), chunk.begin(), chunk.begin() + n);

        // Split off every complete NAL unit. The last one stays in `pending`
        // until the next start code arrives, since only then is it known to be
        // complete.
        size_t codeLen = 0;
        size_t start = findStartCode(pending, 0, &codeLen);
        if (start == std::string::npos) continue;

        size_t cursor = start;
        while (running_.load()) {
            size_t nextLen = 0;
            const size_t next = findStartCode(pending, cursor + codeLen, &nextLen);
            if (next == std::string::npos) break;

            const uint8_t *nal = pending.data() + cursor;
            const size_t nalSize = next - cursor;
            const uint8_t header = pending[cursor + codeLen];

            accessUnit.insert(accessUnit.end(), nal, nal + nalSize);

            if (isVclNal(header)) {
                // Wait for an input buffer rather than dropping the unit.
                // Dropping looks like the low-latency choice but is not: losing
                // one P-frame corrupts the picture until the next IDR, which at
                // one keyframe per second means a second of visible garbage.
                ssize_t inIdx = -1;
                for (int attempt = 0; attempt < 100 && running_.load(); ++attempt) {
                    inIdx = AMediaCodec_dequeueInputBuffer(codec, kDequeueTimeoutUs);
                    if (inIdx >= 0) break;
                }

                if (inIdx >= 0) {
                    size_t capacity = 0;
                    uint8_t *buf = AMediaCodec_getInputBuffer(codec, inIdx, &capacity);
                    const size_t size = accessUnit.size() < capacity ? accessUnit.size() : capacity;
                    if (accessUnit.size() > capacity) {
                        LOGE("access unit %zu bytes does not fit buffer %zu", accessUnit.size(),
                             capacity);
                    }
                    std::memcpy(buf, accessUnit.data(), size);
                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, size, ptsUs, 0);
                    ptsUs += frameDurationUs;
                    unitsSubmitted_.fetch_add(1);
                } else {
                    unitsDropped_.fetch_add(1);
                }
                accessUnit.clear();

                // Periodic stats: without them a stalled stream is
                // indistinguishable from a genuinely static desktop.
                const uint64_t submitted = unitsSubmitted_.load();
                if (submitted > 0 && submitted % 120 == 0) {
                    LOGI("stream: %llu units in, %llu frames out, %llu dropped, %llu KB, backlog %zu",
                         static_cast<unsigned long long>(submitted),
                         static_cast<unsigned long long>(framesRendered_.load()),
                         static_cast<unsigned long long>(unitsDropped_.load()),
                         static_cast<unsigned long long>(bytesReceived_.load() / 1024),
                         pending.size());
                }
            }

            cursor = next;
            codeLen = nextLen;
        }

        pending.erase(pending.begin(), pending.begin() + cursor);
    }

    connected_.store(false);
    running_.store(false);
    if (drainer_.joinable()) drainer_.join();

    AMediaCodec_stop(codec);
    AMediaCodec_delete(codec);
    AMediaFormat_delete(format);
    codec_ = nullptr;

    LOGI("stream stopped: %llu frames, %llu KB received",
         static_cast<unsigned long long>(framesRendered_.load()),
         static_cast<unsigned long long>(bytesReceived_.load() / 1024));
}
