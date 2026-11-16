# Progress

## Status
M0 (scaffold, design system, tabbed shell, sandbox, docs, CI) is complete. Next: M1.

## Done (M0)
- Gradle project mirroring Truck Dash: `:app`, `:sandbox`, shared source dir, wrapper 8.14.3, version catalog (no Room/KSP/lifecycle).
- Design system ported from Truck Dash (Theme, Components, Format, Charts without RouteMap, DesignScale, LocalBackHook), plus `Sparkline` (no axes, colour param, dashed previous-close baseline), `changeColor`, `SwitchRow`, `ValueRow`.
- Tabbed shell MARKETS / NEWS / SETTINGS with placeholder screens built from real components and `DemoData.kt`.
- Markets placeholder is watchlist-first per the owner's requirement: exactly XEQT.TO (CAD) and MSFT (USD), native currency beside the price, sparklines, green gain / red loss, inert "Edit" action in the section header.
- Android app: MainActivity (portrait, keep screen on, immersive, Geist, back hook), Application, manifest (INTERNET + ACCESS_NETWORK_STATE), line-chart adaptive + monochrome icon, dark theme, allowBackup=false.
- Sandbox: window (9:16 frame + minimal panel), headless screenshots (810x1440 per tab), `sandboxJar` (Windows + Linux Skia), `run-sandbox.bat`.
- Tests: `FormatTest` (app, 7 tests) and `SandboxSmokeTest` (sandbox, 3 tests).
- CI workflow uploads APK, sandbox jar + bat, screenshots.

## Next
- **M1** data layer: models, `MarketDataProvider` (Yahoo chart primary, Stooq CSV fallback), HTTP client, file cache with TTL + stale-while-revalidate, fixtures, parsing tests, JSON-file watchlist store with the two defaults (XEQT.TO, MSFT) and a never-lose-user-edits migration policy.
- **M2** Markets UI: watchlist rows, editor (add validated by quote fetch, remove, reorder, rename, quick-add suggestions), instrument detail with ranges, market-hours-aware refresh, stale/offline states, fake provider + controls in sandbox. Delete `DemoData.kt`.
- **M3** News: RSS provider + parser, list/detail, dedupe, time-ago, cache, briefing text builder.
- **M4** Drive safety: GPS speed gate, glance mode, passenger mode (the Settings switch is local state until then), TTS briefing behind an interface.
- **M5** Settings and polish: feed editor, diagnostics, connectivity handling, release artifacts, whole-repo audit.

## Decisions
- Negative numbers use the typographic minus (U+2212), which Geist has; money formatting is pinned to `Locale.US` so output is identical on device and desktop.
- Instruments show value plus ISO currency code in the Numeral unit slot ("41.87 CAD"), mirroring Truck Dash's "12.4 km" pattern; no currency conversion.
- A change that rounds to zero is neither green nor red (`changeSign`).
- Settings rows ("Refresh", "Home currency", "Check connection") and the Passenger mode switch are non-persistent placeholders.
- Launcher icon: thin line chart with a last-price dot on #0A0A0B; same vector doubles as the monochrome layer.
- Shared logic tests live in `app/src/test` like Truck Dash; `:sandbox:test` covers desktop-only wiring.

## Known issues / unverified
- Live providers cannot be reached from the build container; all future provider work is fixture-tested only.
- Launcher icon and real-device rendering were not seen on a device (only headless Skia screenshots).
- The sandbox window itself was built but not opened interactively (headless screenshots only).
