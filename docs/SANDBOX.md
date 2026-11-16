# Desktop sandbox (Windows / Linux)

Runs the **real** shared Markets Board UI on a desktop. `shared/` code is compiled into both the Android app and the sandbox.

## Run (Windows)
1. Install Java 17+ (e.g. Temurin 17 JRE from https://adoptium.net).
2. Put `markets-board-sandbox.jar` and `run-sandbox.bat` in one folder and double-click the `.bat` (or `java -jar markets-board-sandbox.jar`).

Build it yourself: `./gradlew :sandbox:sandboxJar` -> `sandbox/build/libs/markets-board-sandbox.jar` (dev run: `./gradlew :sandbox:run`).
The jar bundles the Windows and Linux Skia renderers only (no macOS), so it runs on Windows and Linux with just `java -jar`. `./gradlew :sandbox:run` uses the host OS renderer.

## What you get (M2)
- Left: the 14.4" portrait head unit as a 9:16 frame (resizable window) running the REAL presenter, refresh controller, repository and watchlist store against `FakeMarketDataProvider` (temp-dir store, nothing touches the network). Right: the control panel.
- Esc triggers the back hook (closes dialogs, add sheet, detail, edit mode, in that order).
- Control panel:
  - **Provider behaviour**: Normal, Slow, Offline, Rate limited, Partial failure, Bad data. Press Refresh in the app (or wait for the next poll) to see rows go stale, dim and amber, then recover.
  - **Latency** slider (0 to 8 s per call).
  - **Clock skew**: the device clock goes 45 min behind the data, which shows the "Check the date and time" banner.
  - **Glance mode**: the minimal view (name, price, change; no editor or detail).
  - **App active**: off pauses all refreshing exactly like a paused Activity.
  - **Time speed** x1 / x10 / x60: runs the data, ages, cooldowns and refresh delays faster, so the 60 s / 5 min policy and the stale timeouts can be watched in seconds.
  - **Simulate hotspot drop (20 s)**: Offline for 20 s, then back to the selected behaviour.
  - **Reset watchlist to defaults**.
- Add a symbol with the real search sheet: the fake catalogue knows the 16 suggestion symbols plus a few more; any other typed symbol still works through Done (the fake serves a quote for any symbol except ones starting with ZZZ, which are "not found").

## What it can NOT tell you
Live provider behaviour (market hosts are unreachable from the build container), real hotspot/LTE handling, touch feel on the actual panel.

## Headless screenshots (for agents / CI)
`./gradlew :sandbox:screenshots` renders Markets / News / Settings PNGs (810x1440) to `sandbox/build/screenshots/` without a window.
Look at every PNG after any UI change.

## Layout
`sandbox/src/main/kotlin/com/marketsboard/sandbox/`: `SandboxApp.kt`, `SandboxEnv.kt`, `ControlPanel.kt`, `FakeMarketDataProvider.kt`, `ScreenshotFixtures.kt`, `Screenshots.kt`, `Fonts.kt`, `Main.kt`. Tests: `sandbox/src/test/.../{RenderTest,MarketsRenderTest,SandboxEnvTest,FakeMarketDataProviderTest,JvmHttpFetcherTest}.kt`.

Screenshots (`./gradlew :sandbox:screenshots`): markets_normal, markets_mixed (stale, failed, loading, rate limited), markets_six (FX, 6-digit price, long name), markets_skew_stale, markets_empty, markets_editor, markets_confirm_remove, detail_1d, detail_1y, detail_loading, detail_error, detail_chart_stale, markets_glance, add_results, add_suggestions, add_offline, replace, news, settings. They are built from `ScreenshotFixtures` (fake provider at a fixed time through the real `UiMapper`), so they are deterministic.
