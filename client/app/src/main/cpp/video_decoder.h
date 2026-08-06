#pragma once

#include <android/native_window.h>

#include <atomic>
#include <string>
#include <thread>

// Декодер файла в ANativeWindow.
//
// Веха B: источник кадров — локальный файл, а не сеть. Это позволяет проверить
// весь путь MediaCodec -> surface swapchain -> цилиндрический слой,
// не имея ещё ни транспорта, ни виртуального дисплея.
//
// В вехе C класс заменяется на приёмник из moonlight-common-c с тем же выходом:
// кадры уходят в тот же ANativeWindow, остальной код не меняется.
class VideoDecoder {
public:
    ~VideoDecoder();

    // window — окно, полученное из Surface у surface swapchain.
    // Декодер отдаёт кадры прямо в него, копий в системную память нет.
    bool start(const std::string &path, ANativeWindow *window);
    void stop();

    // Диагностика: сколько кадров реально доехало до компоситора
    uint64_t framesRendered() const { return framesRendered_.load(); }

private:
    void threadMain();

    std::string path_;
    ANativeWindow *window_ = nullptr;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<uint64_t> framesRendered_{0};
};
