#pragma once

struct android_app;

// Веха A: печатает в logcat, что рантайм умеет и каковы пределы слоёв.
// Ничего не создаёт и не оставляет после себя — безопасно звать на старте.
// Результат прогона на Quest 3 зафиксирован в docs/device-probe.md.
void runCapabilityProbe(android_app *app);
