# Desktop sandbox (Windows / Linux / macOS)

Runs the **real** shared Markets Board UI on a desktop. `shared/` code is compiled into both the Android app and the sandbox.

## Run (Windows)
1. Install Java 17+ (e.g. Temurin 17 JRE from https://adoptium.net).
2. Put `markets-board-sandbox.jar` and `run-sandbox.bat` in one folder and double-click the `.bat` (or `java -jar markets-board-sandbox.jar`).

Build it yourself: `./gradlew :sandbox:sandboxJar` -> `sandbox/build/libs/markets-board-sandbox.jar` (dev run: `./gradlew :sandbox:run`).
The jar bundles the Windows and Linux Skia renderers, so it runs on Windows with just `java -jar`.

## What you get (M0)
- Left: the 14.4" portrait head unit as a 9:16 frame (resizable window). Right: a minimal control panel (grows with M1 to M3: fake provider, stale/offline switches).
- Esc triggers the back hook (used by detail screens from M2).

## What it can NOT tell you
Live provider behaviour (market hosts are unreachable from the build container), real hotspot/LTE handling, touch feel on the actual panel.

## Headless screenshots (for agents / CI)
`./gradlew :sandbox:screenshots` renders Markets / News / Settings PNGs (810x1440) to `sandbox/build/screenshots/` without a window.
Look at every PNG after any UI change.

## Layout
`sandbox/src/main/kotlin/com/marketsboard/sandbox/`: `SandboxApp.kt`, `Screenshots.kt`, `Fonts.kt`, `Main.kt`. Tests: `sandbox/src/test/.../SandboxSmokeTest.kt`.
