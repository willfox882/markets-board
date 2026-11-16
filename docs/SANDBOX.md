# Desktop sandbox (Windows / Linux)

Runs the **real** shared Markets Board UI on a desktop. `shared/` code is compiled into both the Android app and the sandbox.

## Run (Windows)
1. Install Java 17+ (e.g. Temurin 17 JRE from https://adoptium.net).
2. Put `markets-board-sandbox.jar` and `run-sandbox.bat` in one folder and double-click the `.bat` (or `java -jar markets-board-sandbox.jar`).

Build it yourself: `./gradlew :sandbox:sandboxJar` -> `sandbox/build/libs/markets-board-sandbox.jar` (dev run: `./gradlew :sandbox:run`).
The jar bundles the Windows and Linux Skia renderers only (no macOS), so it runs on Windows and Linux with just `java -jar`. `./gradlew :sandbox:run` uses the host OS renderer.

## What you get (M2 and M3)
- Left: the 14.4" portrait head unit as a frame of the chosen pane shape (Full 9:16 by default; the control panel's "Pane shape" chips and slider give the top/bottom half 9:8 and the short strip 3:2; resizable window) running the REAL presenter, refresh controller, repository and watchlist store against `FakeMarketDataProvider` (temp-dir store, nothing touches the network). Right: the control panel.
- Esc triggers the back hook (closes dialogs, add sheet, detail, edit mode, in that order).
- Control panel:
  - **Market behaviour**: Normal, Slow, Offline, Rate limited, Partial failure, Bad data. Press Refresh in the app (or wait for the next poll) to see rows go stale, dim and amber, then recover.
  - **News behaviour** (M3): Normal, Slow (6 s per feed), Offline (the hotspot is gone: headlines are kept and the News tab turns amber), Malformed feeds (Yahoo answers with a hotspot login page, The Globe and Mail with a truncated Atom feed that is partly readable), One feed failing (CBC answers 503 and backs off). Use Refresh on the News tab (or Retry) to see each.
  - **Add breaking headline** (M3): puts a fresh item at the top of the CBC, Yahoo and MarketWatch feeds; press Refresh on the News tab. The same story set always contains cross-feed duplicates, watchlist stories (Microsoft, XEQT, iShares), CDATA and entity text, odd date formats and one very long title.
  - **Latency** slider (0 to 8 s per call).
  - **Clock skew**: the device clock goes 45 min behind the data, which shows the "Check the date and time" banner.
  - **Driving** (M4): a speed slider (0 to 130 km/h) feeds the REAL `DrivingGate` once a second. Above 5 km/h every presenter goes to Glance (name, price, change; top 3 headlines; Settings dead; large "Brief me"); it unlocks after 10 s at 2 km/h or less. "Gate: Glance (Moving)" shows the reason. The gate starts in "Checking" (Glance) until the first sample; **GPS lost** while locked for 60 s shows the 3 s "I am parked" hold; **GPS lost** stops the samples (a locked screen stays locked), **No location permission** shows the "cannot know" state (Settings gets the amber warning and "Grant location permission", which grants it here), **Passenger mode** is the same setting as in Settings (blocked while locked).
  - **Speaker** (M4): Speech ok, No engine (text fallback), Error mid-way (engine dies halfway: text), Focus lost mid-way (another app takes the audio: stops with a note). The speaker is silent: the caption in the real UI and the "Speaker log" in the panel show what would be said. Time speed also speeds the speech.
  - **App active**: off pauses all refreshing exactly like a paused Activity.
  - **Time speed** x1 / x10 / x60: runs the data, ages, cooldowns and refresh delays faster, so the 60 s / 5 min policy and the stale timeouts can be watched in seconds.
  - **Simulate hotspot drop (20 s)**: markets AND news go Offline for 20 s, then back to the selected behaviour, and the "network is back" signal fires (failed feeds and quotes retry at once).
  - **Reset watchlist to defaults**.
- Add a symbol with the real search sheet: the fake catalogue knows the 16 suggestion symbols plus a few more; any other typed symbol still works through Done (the fake serves a quote for any symbol except ones starting with ZZZ, which are "not found").

- The stats block shows the number of news fetches (and how many were answered 304) and the last link the article screen tried to open ("Open article"; the desktop browser is launched when the machine has one).
- **Time speed** also speeds the news poll (10 min becomes 1 min at x10).

## What it can NOT tell you
Live provider and live feed behaviour (market and news hosts are unreachable from the build container; the fake news servers are written from knowledge of the formats), real hotspot/LTE handling, touch feel on the actual panel.

## Headless screenshots (for agents / CI)
`./gradlew :sandbox:screenshots` renders Markets / News / Settings PNGs (810x1440) to `sandbox/build/screenshots/` without a window.
Look at every PNG after any UI change.

## Layout
`sandbox/src/main/kotlin/com/marketsboard/sandbox/`: `SandboxApp.kt`, `SandboxEnv.kt`, `ControlPanel.kt`, `FakeMarketDataProvider.kt`, `FakeFeedFetcher.kt`, `FakeSpeaker.kt`, `ScreenshotFixtures.kt`, `Screenshots.kt`, `Fonts.kt`, `Main.kt`. Tests: `sandbox/src/test/.../{RenderTest,MarketsRenderTest,NewsRenderTest,DrivingRenderTest,SandboxEnvTest,SandboxNewsEnvTest,SandboxDrivingEnvTest,FakeMarketDataProviderTest,FakeFeedFetcherTest,JvmHttpFetcherTest}.kt`.

Screenshots (`./gradlew :sandbox:screenshots`): markets_normal, markets_mixed (stale, failed, loading, rate limited), markets_six (FX, 6-digit price, long name), markets_skew_stale, markets_empty, markets_editor, markets_confirm_remove, detail_1d, detail_1y, detail_loading, detail_error, detail_chart_stale, markets_glance (now with the Driving label and Brief me), add_results, add_suggestions, add_offline, replace, settings, and the News set: news, news_long_titles, news_my_symbols, news_my_symbols_empty, news_loading, news_empty, news_error, news_stale, news_detail, news_glance. The News ones run the REAL repository against `FakeFeedFetcher` at the fixed time (stale = a good pass, then 40 minutes offline). They are built from `ScreenshotFixtures` (fake provider at a fixed time through the real `UiMapper`), so they are deterministic.

M4 screenshots: markets_glance, markets_glance_two, news_glance, settings_parked, settings_permission_missing, settings_passenger, briefing_normal_speaking, briefing_normal_preparing, briefing_glance_speaking, briefing_glance_news_speaking, briefing_glance_preparing, briefing_text_fallback, briefing_glance_unavailable, passenger_label (the old `settings` shot stays, with the default unknown driving status).
