#include "video_decoder.h"

#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaCodec.h>

#include <chrono>
#include <cstring>
#include <thread>

#include "log.h"

namespace {

// Keep dequeue timeouts short: long ones turn into visible hitching at startup
constexpr int64_t kDequeueTimeoutUs = 10000;

int64_t nowUs() {
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

}  // namespace

VideoDecoder::~VideoDecoder() {
    stop();
}

bool VideoDecoder::start(const std::string &path, ANativeWindow *window) {
    if (running_.load()) return false;
    if (window == nullptr) {
        LOGE("decoder got no output window");
        return false;
    }

    path_ = path;
    window_ = window;
    running_.store(true);
    thread_ = std::thread(&VideoDecoder::threadMain, this);
    return true;
}

void VideoDecoder::stop() {
    running_.store(false);
    if (thread_.joinable()) thread_.join();
}

void VideoDecoder::threadMain() {
    AMediaExtractor *extractor = AMediaExtractor_new();
    if (extractor == nullptr) {
        LOGE("AMediaExtractor_new failed");
        return;
    }

    FILE *f = fopen(path_.c_str(), "rb");
    if (f == nullptr) {
        LOGE("cannot open file: %s", path_.c_str());
        AMediaExtractor_delete(extractor);
        return;
    }
    fseek(f, 0, SEEK_END);
    const off64_t size = ftello(f);
    fseek(f, 0, SEEK_SET);

    media_status_t st = AMediaExtractor_setDataSourceFd(extractor, fileno(f), 0, size);
    if (st != AMEDIA_OK) {
        LOGE("setDataSourceFd -> %d", st);
        fclose(f);
        AMediaExtractor_delete(extractor);
        return;
    }

    int videoTrack = -1;
    const char *mime = nullptr;
    AMediaFormat *format = nullptr;
    const size_t trackCount = AMediaExtractor_getTrackCount(extractor);
    for (size_t i = 0; i < trackCount; ++i) {
        AMediaFormat *fmt = AMediaExtractor_getTrackFormat(extractor, i);
        const char *m = nullptr;
        if (AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &m) && strncmp(m, "video/", 6) == 0) {
            videoTrack = static_cast<int>(i);
            mime = m;
            format = fmt;
            break;
        }
        AMediaFormat_delete(fmt);
    }

    if (videoTrack < 0) {
        LOGE("no video track in %s", path_.c_str());
        fclose(f);
        AMediaExtractor_delete(extractor);
        return;
    }

    int32_t width = 0, height = 0;
    AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_WIDTH, &width);
    AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_HEIGHT, &height);
    LOGI("decoder: %s %dx%d", mime, width, height);

    AMediaExtractor_selectTrack(extractor, videoTrack);

    AMediaCodec *codec = AMediaCodec_createDecoderByType(mime);
    if (codec == nullptr) {
        LOGE("no hardware decoder for %s", mime);
        AMediaFormat_delete(format);
        fclose(f);
        AMediaExtractor_delete(extractor);
        return;
    }

    // The key part: the window goes straight into configure. A decoded frame
    // lands in the compositor's swapchain without passing through system memory.
    st = AMediaCodec_configure(codec, format, window_, nullptr, 0);
    if (st != AMEDIA_OK) {
        LOGE("AMediaCodec_configure -> %d", st);
        AMediaCodec_delete(codec);
        AMediaFormat_delete(format);
        fclose(f);
        AMediaExtractor_delete(extractor);
        return;
    }
    AMediaCodec_start(codec);

    bool inputDone = false;
    int64_t firstPtsUs = -1;
    int64_t startWallUs = 0;

    while (running_.load()) {
        if (!inputDone) {
            const ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, kDequeueTimeoutUs);
            if (inIdx >= 0) {
                size_t bufSize = 0;
                uint8_t *buf = AMediaCodec_getInputBuffer(codec, inIdx, &bufSize);
                const ssize_t sampleSize = AMediaExtractor_readSampleData(extractor, buf, bufSize);

                if (sampleSize < 0) {
                    // End of file — loop, so the picture can be studied without
                    // restarting the app
                    AMediaExtractor_seekTo(extractor, 0, AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);
                    firstPtsUs = -1;
                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, 0, 0, 0);
                } else {
                    const int64_t ptsUs = AMediaExtractor_getSampleTime(extractor);
                    AMediaCodec_queueInputBuffer(codec, inIdx, 0, sampleSize, ptsUs, 0);
                    AMediaExtractor_advance(extractor);
                }
            }
        }

        AMediaCodecBufferInfo info;
        const ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, kDequeueTimeoutUs);
        if (outIdx >= 0) {
            // Playback paced by presentation time. A live stream does the
            // opposite — see StreamDecoder, where latency beats smoothness.
            if (firstPtsUs < 0) {
                firstPtsUs = info.presentationTimeUs;
                startWallUs = nowUs();
            }
            const int64_t targetUs = startWallUs + (info.presentationTimeUs - firstPtsUs);
            const int64_t deltaUs = targetUs - nowUs();
            if (deltaUs > 1000 && deltaUs < 1000000) {
                std::this_thread::sleep_for(std::chrono::microseconds(deltaUs));
            }

            // true — hand the frame to the window. This is the output to the
            // compositor.
            AMediaCodec_releaseOutputBuffer(codec, outIdx, true);
            framesRendered_.fetch_add(1);
        } else if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *newFmt = AMediaCodec_getOutputFormat(codec);
            LOGI("output format changed: %s", AMediaFormat_toString(newFmt));
            AMediaFormat_delete(newFmt);
        }
    }

    AMediaCodec_stop(codec);
    AMediaCodec_delete(codec);
    AMediaFormat_delete(format);
    AMediaExtractor_delete(extractor);
    fclose(f);
    LOGI("decoder stopped, frames delivered: %llu",
         static_cast<unsigned long long>(framesRendered_.load()));
}
