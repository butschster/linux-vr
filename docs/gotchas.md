# Грабли

Что уже сломалось и как чинилось. Пополняется по ходу.

## Quest / Horizon OS

### Moonlight падает при старте на любой версии

`com.limelight` умирает в `onCreate`, панель даже не отрисовывается:

```
java.lang.NullPointerException: Attempt to invoke virtual method
'void android.app.GameManager.setGameState(android.app.GameState)' on a null object reference
    at com.limelight.utils.UiHelper.setGameModeStatus(UiHelper.java:40)
    at com.limelight.utils.UiHelper.notifyNewRootView(UiHelper.java:118)
    at com.limelight.PcView.completeOnCreate(PcView.java:245)
```

Moonlight запрашивает системный сервис `GameManager` (Android 13+) и не проверяет
результат на `null`. Horizon OS этот сервис не предоставляет.

**Откат на старые версии не помогает.** Проверено: v12.1, v11.0 — падают одинаково;
`GameManager` присутствует в dex вплоть до v10.8.4 (сентябрь 2022). Причина не в
версии Moonlight: код защищён проверкой уровня API, но **Horizon OS рапортует API 34**,
проверка проходит, а `getSystemService` возвращает `null`.

**Решение:** собирать из исходников с проверкой на `null` в `UiHelper.setGameModeStatus`.
Заодно в дереве оказывается `moonlight-common-c`, который нужен для вехи C.

```sh
git clone --recurse-submodules --depth 1 \
    https://github.com/moonlight-stream/moonlight-android.git vendor/moonlight-android
cd vendor/moonlight-android
echo "sdk.dir=/home/butschster/Dev/Android/Sdk" > local.properties
# в app/build.gradle: ndkVersion -> установленная версия (27.2.12479018)
# в UiHelper.setGameModeStatus: ранний return, если gameManager == null
./gradlew assembleNonRootDebug
adb install -r app/build/outputs/apk/nonRoot/debug/app-nonRoot-debug.apk
```

Debug-сборка ставится под **другим** applicationId — `com.limelight.debug`,
а не `com.limelight`. Запуск: `am start -n com.limelight.debug/com.limelight.PcView`.

Своего клиента это не касается — он нативный и `GameManager` не трогает.
Урок общий: **уровень API на Horizon OS не гарантирует наличия системного сервиса.**
Проверять фактом, а не `Build.VERSION`.

### Horizon OS блокирует запуск, если не заявлена поддержка рук

`am start` отрабатывает, но приложение не стартует. В логе:

```
ActivityLaunchInterceptorController: RequiresControllersLaunchInterceptor
CaseDialogAnalytics: dialogId=common_system_dialog_app_launch_blocked_controller_required
```

Система решает, что приложению нужны контроллеры, и блокирует запуск, когда
пользователь работает руками. Для VR-десктопа это ровно тот случай: контроллеры
лежат на столе, руки на клавиатуре.

**Решение** — в манифест:

```xml
<uses-feature android:name="oculus.software.handtracking" android:required="false" />
<uses-permission android:name="com.oculus.permission.HAND_TRACKING" />
```

Хендтрекинг при этом использовать не обязательно — важен сам факт объявления.

### `xrCreateSwapchainAndroidSurfaceKHR` требует нулей в полях формата

Возвращает `-1` (`XR_ERROR_VALIDATION_FAILURE`), если заполнить
`XrSwapchainCreateInfo` как для обычного swapchain.

Для surface swapchain задаются **только `width` и `height`**. Поля `format`,
`sampleCount`, `faceCount`, `arraySize`, `mipCount` обязаны остаться нулевыми —
содержимым распоряжается производитель кадров (`MediaCodec`), а не приложение.
Привычные единицы в `faceCount`/`arraySize`/`mipCount` рантайм отвергает.

### 2D-приложения не всегда стартуют через `am start`

`adb shell am start` для 2D-приложения отрабатывает без ошибки, но VR-оболочка может
не создать для него панель. Признак — активность стартовала, а процесс мёртв.

Проверять фактом, а не кодом возврата:

```sh
adb shell pidof com.limelight && echo ЖИВ || echo УМЕР
```

Иммерсивных приложений (категория `IMMERSIVE_HMD`) это не касается — они запускаются
через `am start` штатно.

### Пинг до спящей гарнитуры врёт

RTT до Quest скачет от 21 мс до 1.6 с, когда шлем не надет — Wi-Fi power saving.
Мерить связность только на надетой гарнитуре, иначе диагностика уводит в сторону
несуществующих проблем с сетью.

### `/dev/tcp` в shell гарнитуры не работает

Проверка порта через `echo > /dev/tcp/host/port` выдаёт `No such file or directory` —
это ограничение shell в Android, а не закрытый порт. Пользоваться другим способом.

## Хост

### Vulkan Video encode на RADV подвешивает GPU

**Симптом:** через секунду после старта стрима — сброс GPU и выброс из сессии
в графический логин.

```
amdgpu 0000:06:00.0: GPU reset(1) succeeded!
amdgpu 0000:06:00.0: [drm] device wedged, but recovered through reset
amdgpu 0000:06:00.0: [drm] *ERROR* Failed to initialize parser -125!
REG_WAIT timeout 1us * 100 tries - dcn31_program_compbuf_size
WARNING: dcn31_hubbub.c:151 at dcn31_program_compbuf_size
```

Sunshine при этом теряет Wayland (`Error reading events from display: Broken pipe`)
и падает с кодом 1.

**Причина:** Sunshine по умолчанию выбирает `h264_vulkan` / `hevc_vulkan` — путь
Vulkan Video на RADV. На Rembrandt (VCN 3.0) он вешает видеодвижок.

**Решение:** принудительно VAAPI в `~/.config/sunshine/sunshine.conf`:

```
encoder = vaapi
```

VAAPI на этом железе проверен отдельно — `ffmpeg` гонял `h264_vaapi` 1440p90
при CBR 100 Мбит десять секунд без единой ошибки, 160 fps.

### После сброса GPU Sunshine перезапускается в вакууме

systemd поднимает сервис раньше, чем восстанавливается графическая сессия.
В логе тогда:

```
Error: Couldn't open: /dev/dri/card1: Permission denied
Error: [wayland] Environment variable WAYLAND_DISPLAY has not been defined
Warning: [portalgrab] Failed to connect to dbus
Fatal: Unable to find display or encoder during startup
```

Все энкодеры отчитываются как `failed`, хотя с железом всё в порядке.
**После логина Sunshine нужно перезапустить руками:**

```sh
systemctl --user restart app-dev.lizardbyte.app.Sunshine.service
```

### Sunshine: юнит называется иначе

Не `sunshine.service`, а **`app-dev.lizardbyte.app.Sunshine.service`** — переименован
в поздних релизах. Старые инструкции из интернета не работают.

### Sunshine настраивается без браузера

```sh
sunshine --creds <логин> <пароль>          # учётка веб-интерфейса
curl -sk -u '<логин>:<пароль>' https://localhost:47990/api/apps
curl -sk -u '<логин>:<пароль>' https://localhost:47990/api/clients/list
```

Сертификат самоподписанный — `curl` нужен с `-k`.

## Сборка

### В системе стоял JRE без `javac`

Gradle падал на `Toolchain installation ... does not provide the required capabilities:
[JAVA_COMPILER]`. Лечится `apt install openjdk-21-jdk-headless`.

### Gradle кеширует обнаружение тулчейнов

После установки JDK сборка падала с той же ошибкой, пока не погашен демон:

```sh
./gradlew --stop
```
