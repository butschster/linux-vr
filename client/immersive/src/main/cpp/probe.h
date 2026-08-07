#pragma once

struct android_app;

// Milestone A: prints what the runtime can do and what the layer limits are.
// Creates nothing and leaves nothing behind — safe to call at startup.
// Results from the Quest 3 run are recorded in docs/device-probe.md.
void runCapabilityProbe(android_app *app);
