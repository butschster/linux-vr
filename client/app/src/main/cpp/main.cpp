// Веха B: кадр из MediaCodec в цилиндрический слой компоситора.
//
// Сети ещё нет — источник кадров локальный файл. Проверяется единственное,
// но главное: путь декодер -> surface swapchain -> composition layer,
// и читаемость текста на слое, ради которой всё и затевалось.
//
// В вехе C VideoDecoder меняется на приёмник moonlight-common-c.
// Остальной код остаётся как есть: выход у обоих один и тот же ANativeWindow.

#include <android_native_app_glue.h>

#include <string>

#include "log.h"
#include "video_decoder.h"
#include "xr_app.h"

namespace {

// Разрешение слоя. 1440p — не «поменьше, чтобы влезло», а осознанный потолок:
// Quest 3 даёт ~1680x1760 на глаз, монитор шириной 50° физически не покажет
// больше ~1250 пикселей по горизонтали. Точную цифру стоит взять у рантайма
// через XR_META_recommended_layer_resolution — расширение на устройстве есть.
constexpr uint32_t kLayerWidth = 2560;
constexpr uint32_t kLayerHeight = 1440;

// Снимок реального рабочего стола Ubuntu на штатных настройках (масштаб 1.0,
// интерфейс Ubuntu Sans 11 ≈ 14.7 px, терминал Ubuntu Sans Mono 13 ≈ 17.3 px).
// Синтетический шаблон с кеглями лежит рядом под именем testpattern.mp4.
constexpr const char *kTestPatternName = "desktop.mp4";

struct AppState {
    XrApp xr;
    VideoDecoder decoder;
    bool resumed = false;
    bool decoderStarted = false;
};

void onAppCmd(android_app *app, int32_t cmd) {
    auto *state = static_cast<AppState *>(app->userData);
    if (state == nullptr) return;

    switch (cmd) {
        case APP_CMD_RESUME:
            state->resumed = true;
            LOGI("активность возобновлена");
            break;
        case APP_CMD_PAUSE:
            state->resumed = false;
            LOGI("активность приостановлена");
            break;
        case APP_CMD_INIT_WINDOW:
            LOGI("окно активности создано");
            break;
        case APP_CMD_TERM_WINDOW:
            LOGI("окно активности уничтожено");
            break;
        case APP_CMD_DESTROY:
            LOGI("активность уничтожается");
            state->decoder.stop();
            break;
        default:
            break;
    }
}

}  // namespace

void android_main(android_app *app) {
    LOGI("======== linux-vr, веха B ========");

    AppState state;
    app->userData = &state;
    app->onAppCmd = onAppCmd;

    if (!state.xr.init(app)) {
        LOGE("инициализация OpenXR провалилась, выходим");
        return;
    }

    state.xr.geometry.radius = 1.5f;
    // 66° выведены из замера: штатный шрифт Ubuntu (~15 px) попадает при этом
    // угле ровно в измеренный порог комфорта 0.39°. См. docs/readability.md.
    state.xr.geometry.horizontalFovDegrees = 66.0f;
    state.xr.geometry.heightOffset = 0.0f;

    ANativeWindow *window = state.xr.createVideoSurface(kLayerWidth, kLayerHeight);
    if (window == nullptr) {
        LOGE("surface swapchain не создался, выходим");
        state.xr.shutdown();
        return;
    }

    // Каталог приложения во внешнем хранилище — туда кладём тестовый файл.
    // Разрешений не требует, в отличие от общего /sdcard.
    const std::string path = std::string(app->activity->externalDataPath) + "/" + kTestPatternName;
    LOGI("ищу тестовый файл: %s", path.c_str());

    while (!app->destroyRequested) {
        // Осушаем очередь Android-событий.
        //
        // Блокироваться здесь можно ТОЛЬКО когда активность приостановлена
        // и сессия не идёт: состояние сессии приходит через xrPollEvent,
        // а не через Android-лупер. Заблокируешься с таймаутом -1 в момент,
        // когда рантайм собирается прислать READY, — и не дождёшься никогда,
        // потому что ждёшь не по тому каналу.
        for (;;) {
            const int timeout =
                (!state.resumed && !state.xr.sessionRunning() && !app->destroyRequested) ? -1 : 0;

            int events = 0;
            android_poll_source *source = nullptr;
            if (ALooper_pollOnce(timeout, nullptr, &events,
                                 reinterpret_cast<void **>(&source)) < 0) {
                break;
            }
            if (source != nullptr) source->process(app, source);
        }

        if (!state.xr.pollEvents(app)) break;

        // Декодер запускаем только когда сессия действительно пошла:
        // иначе первые кадры уедут в никуда и счётчик соврёт.
        if (state.xr.sessionRunning() && !state.decoderStarted) {
            if (state.decoder.start(path, window)) {
                state.decoderStarted = true;
                LOGI("декодер запущен");
            } else {
                LOGE("декодер не стартовал — файл на месте? %s", path.c_str());
                state.decoderStarted = true;  // не долбимся в цикле
            }
        }

        state.xr.renderFrame();
    }

    LOGI("кадров отдано декодером: %llu",
         static_cast<unsigned long long>(state.decoder.framesRendered()));

    state.decoder.stop();
    state.xr.shutdown();
    LOGI("======== завершение ========");
}
