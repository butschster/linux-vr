# Хост: измеренный baseline

Снято 2026-08-06 на целевой машине. Всё ниже — факт с машины, не спецификация вендора.

## Железо

| | |
|---|---|
| Модель | Beelink EQ (DMI: `AZW` / `EQ`), chassis type 3 — desktop |
| CPU | AMD Ryzen 7 6800U, 16 потоков |
| GPU | Radeon 680M (Rembrandt, `1002:1681`), VCN 3.0 |
| RAM | 27 GiB |
| Диск | 236 GiB свободно |
| Ethernet | 2× Realtek RTL8111 — оба `NO-CARRIER` |
| Wi-Fi | Intel AX200 (Wi-Fi 6, без 6 ГГц), 5 ГГц ch40 |

Сеть намеренно вне рассмотрения: рабочий эталон Wi-Fi↔Wi-Fi уже подтверждён на Windows.

## ОС и сессия

| | |
|---|---|
| ОС | Ubuntu 24.04.4 LTS, ядро 7.0.0-28-generic |
| Сессия | **Wayland**, GNOME Shell 46.0 |
| Mesa | 25.2.8 (radeonsi) |
| PipeWire | 1.0.5, `pipewire` + `wireplumber` активны |
| Порталы | `gnome.portal`, `gtk.portal` (нет `wlr`) |
| gnome-remote-desktop | 46.3 установлен, выключен |

## Энкодер

VCN 3.0, кольца `vcn_enc_0.0` / `vcn_enc_0.1`. Прошивка ENC 1.30, DEC 3.

VA-API (`renderD128`, после установки `mesa-va-drivers`):

- `VAProfileH264ConstrainedBaseline` — `VAEntrypointEncSlice`
- `VAProfileH264Main` — `VAEntrypointEncSlice`
- `VAProfileH264High` — `VAEntrypointEncSlice`
- `VAProfileHEVCMain` — `VAEntrypointEncSlice`
- `VAProfileHEVCMain10` — `VAEntrypointEncSlice`

**AV1-энкода нет** — ожидаемо для VCN 3.0 (декод есть, энкод только с VCN 4).

`EncSlice` важен: позволяет отдавать слайсы по мере готовности, не дожидаясь конца кадра.

### Замер пропускной способности

```
ffmpeg -vaapi_device /dev/dri/renderD128 \
  -f lavfi -i testsrc2=size=2560x1440:rate=90 -t 10 \
  -vf format=nv12,hwupload \
  -c:v h264_vaapi -profile:v high -rc_mode CBR -b:v 100M -bf 0 -g 9999 -f null -
```

Результат: **160 fps** устойчиво. К целевым 90 Гц — запас 1.78×.

Оценка пессимистична: в тесте кадры генерировались на CPU и заливались через `hwupload`.
В реальном пайплайне PipeWire/KMS отдаёт DMA-BUF напрямую, копии через системную память нет.

## Дисплеи

| Коннектор | Статус | Режим |
|---|---|---|
| `HDMI-A-1` | connected | 2560×1440 |
| `HDMI-A-2` | connected | 2560×1440 |
| `DP-1` … `DP-7` | disconnected | — |
| `Writeback-1` | unknown | — |

**Осторожно с DP.** Семь свободных коннекторов — это слоты дисплейного контроллера DCN,
а не физические порты. На корпусе выведены 2× HDMI и USB-C. Форсирование
`video=DP-N:e` на amdgpu требует успешного link training, которого без реального
приёмника не будет. Считать путь EDID-инжекта решённым нельзя — только проверить.

## Ввод

`/dev/uinput` — `root:input`, пользователь состоит в группе `input`.
Инъекция ввода через evdev доступна без sudo и без зависимости от композитора.

## Установленное под задачу

- `mesa-va-drivers`, `vainfo`, `ffmpeg` 6.1.1
- `sunshine` 2026.516.143833 (`.deb` с GitHub-релизов, не из репозиториев)
- `adb` 1.0.41, `ninja-build`
- Android SDK / NDK 27.2.12479018 / CMake 3.22.1 в `~/Android/Sdk`

### Sunshine

Юнит называется `app-dev.lizardbyte.app.Sunshine.service` (не `sunshine.service` —
переименован в поздних релизах). Включён в `graphical-session.target`.

При старте выбирает:

- **Захват:** KMS + DMA-BUF (`Screencasting with KMS`), zero-copy, без портального
  диалога — работает благодаря `CAP_SYS_ADMIN`, который проставил postinst.
- **Энкодер:** Vulkan Video (`h264_vulkan`, `hevc_vulkan`) на RADV, **не** VAAPI.
  `av1_vulkan` не открывается — железо не умеет.

Vulkan-путь на RADV новее и менее обкатан, чем VAAPI. Если замеры покажут проблемы
с латентностью или качеством — в конфиге принудительно переключить на VAAPI,
оба энкодера присутствуют в системе.

Ограничение: KMS-захват берёт **физический** выход. До появления виртуального
дисплея стрим — зеркало одного из двух 2560×1440.
