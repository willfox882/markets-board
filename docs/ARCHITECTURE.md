# Architecture: file map (one line per file)

## Root
- `CLAUDE.md`: agent entry point (commands, conventions, design rules).
- `README.md`: human overview.
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`: Gradle setup and version catalog.
- `local.properties`: sdk.dir (gitignored). `.gitignore`: build outputs, local.properties, *.apk.
- `.github/workflows/build.yml`: CI (tests, assembleDebug, lintDebug, sandboxJar, artifacts).
- `docs/PLAYBOOK.md`: orchestrator brief and milestone plan (do not edit). `docs/PROGRESS.md`: state and decisions. `docs/SANDBOX.md`: desktop simulator. `docs/licenses/OFL-Geist.txt`: font licence.

## shared/ (compiled by both :app and :sandbox)
- `shared/res/font/geist_{light,regular,medium,semibold}.ttf`: Geist weights (Android `R.font`, desktop classpath `font/`).
- `shared/src/main/kotlin/com/marketsboard/ui/Theme.kt`: `Dash` palette, `Tabular`, `DashFonts` hook, `MarketsBoardTheme`.
- `.../ui/Components.kt`: Label, ScreenTitle, Hairline, Dot, DashCard, Numeral, StatGrid, PillButton, TextAction, SectionHeader, SwitchRow, ValueRow, DashDialog, `changeColor`.
- `.../ui/Format.kt`: pure formatting (formatPrice, formatSigned, formatPercent, changeSign, formatTimeAgo), typographic minus.
- `.../ui/DesignScale.kt`: `DesignScale` (440 dp design width) and `LocalBackHook`.
- `.../ui/MarketsBoardRoot.kt`: `MbTab` enum and the tabbed shell with hairline bottom bar.
- `.../ui/charts/Charts.kt`: `LineChart`, `BarChart`, `Sparkline`, `downsample`.
- `.../ui/markets/MarketsScreen.kt`: watchlist-first Markets screen (M0 placeholder, uses DemoData).
- `.../ui/news/NewsScreen.kt`: News screen (M0 placeholder, three demo headlines).
- `.../ui/settings/SettingsScreen.kt`: Settings screen (M0 placeholder with local-state switch and pill button).
- `.../ui/DemoData.kt`: throwaway demo content for M0 (XEQT.TO in CAD, MSFT in USD, headlines). Delete when M1/M2 land.

## app/ (Android)
- `app/build.gradle.kts`: Android module, shared srcDir + font res.
- `app/src/main/AndroidManifest.xml`: INTERNET + ACCESS_NETWORK_STATE only, portrait activity.
- `app/src/main/java/com/marketsboard/MainActivity.kt`: immersive, keep-screen-on, sets fonts, provides `LocalBackHook`, shows the root.
- `app/src/main/java/com/marketsboard/MarketsBoardApplication.kt`: Application (empty until M1).
- `app/src/main/res/`: `values/themes.xml` (window bg #0A0A0B), `values/colors.xml`, `drawable/ic_launcher_fg.xml` (line chart glyph), `mipmap-anydpi-v26/ic_launcher.xml` (adaptive + monochrome).
- `app/src/test/kotlin/com/marketsboard/ui/FormatTest.kt`: unit tests for Format, changeColor and the demo watchlist.

## sandbox/ (desktop JVM)
- `sandbox/build.gradle.kts`: Compose Multiplatform desktop, `screenshots` and `sandboxJar` tasks.
- `sandbox/run-sandbox.bat`: Windows launcher for the jar.
- `sandbox/src/main/kotlin/com/marketsboard/sandbox/Main.kt`: window entry point.
- `.../sandbox/SandboxApp.kt`: 9:16 head-unit frame running the real UI + control panel.
- `.../sandbox/Fonts.kt`: `loadDashFonts()` from classpath.
- `.../sandbox/Screenshots.kt`: headless PNG per tab to `sandbox/build/screenshots/`.
- `sandbox/src/test/kotlin/com/marketsboard/sandbox/SandboxSmokeTest.kt`: shell tabs, format helpers and font resources on desktop.
