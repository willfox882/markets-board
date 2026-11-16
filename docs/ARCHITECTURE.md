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
- `.../ui/Format.kt`: pure formatting (formatPrice, formatSigned, formatPercent, changeSign, formatTimeAgo), typographic minus; one BigDecimal rounding step shared by text, sign and colour.
- `.../ui/DesignScale.kt`: `DesignScale` (440 dp design width) and `LocalBackHook`.
- `.../ui/MarketsBoardRoot.kt`: `MbTab` enum and the tabbed shell with hairline bottom bar; takes `MarketsUiState` + `NewsUiState` from the caller.
- `.../ui/charts/Charts.kt`: `LineChart`, `BarChart`, `Sparkline`, `downsample`.
- `.../ui/markets/MarketsUi.kt`: `QuoteRowUi` (change derived from price and prevClose in one place) and `MarketsUiState`.
- `.../ui/markets/MarketsScreen.kt`: stateless watchlist-first Markets screen, `QuoteRow`, `priceFontSizeSp`.
- `.../ui/news/NewsUi.kt`: `HeadlineUi` and `NewsUiState`.
- `.../ui/news/NewsScreen.kt`: stateless headlines list.
- `.../ui/settings/SettingsScreen.kt`: Settings placeholder (local-state switch, value rows, pill button).
- `.../data/Clock.kt`: injectable clock: wall `nowMs` (persisted times, skew detection) and monotonic `monoMs` (in-process ages, throttling, cooldowns); never used for market timestamps.
- `.../data/model/Models.kt`: `Instrument`, `WatchItem`, `ChartRange` (+ Yahoo range/interval), `SeriesPoint`, `Quote` (provider-time `asOfEpochMs`, `source`, session window, timezone, `priceHint`/`decimals`), `Currencies` (GBp to GBX etc), `ErrorKind` (incl. `ClockOrTls`), `FetchResult` (Ok/Err), `QuoteValidator` (price/series sanity rules).
- `.../data/net/HttpFetcher.kt`: `HttpFetcher` interface, `HttpResponse` (typed `failure`, `toError()`, parsed Retry-After), `hostOf` (log-safe host).
- `.../data/net/JvmHttpFetcher.kt`: HttpURLConnection implementation: timeouts, gzip, 20 s whole-fetch deadline, disconnect on cancel, manual redirects (max 3, no scheme downgrade), 2 MB cap, error bodies, `offline` and cert-date detection, never throws.
- `.../data/net/FetchLog.kt`: `FetchEvent`, `FetchLog` (100-event ring buffer as StateFlow) and `LoggingHttpFetcher` decorator (host only, latency, outcome).
- `.../data/provider/MarketDataProvider.kt`: provider interface (`quote`, `search`, `supports(symbol)` and `supports(symbol, range)`).
- `.../data/provider/JsonSupport.kt`: defensive accessors over the kotlinx.serialization `JsonElement` tree (wrong type or null becomes null).
- `.../data/provider/YahooChartProvider.kt`: Yahoo chart + search provider (query1, query2 fallback host) and the pure `YahooParser`.
- `.../data/provider/StooqProvider.kt`: best-effort Stooq CSV fallback, `YahooToStooq` symbol mapping, `StooqParser`.
- `.../data/store/FileJsonCache.kt`: directory cache: atomic writes, per-entry TTL, total size cap with oldest-first eviction, corrupt tolerant.
- `.../data/store/QuoteCodec.kt`: JSON encode/decode (re-validating) of a `Quote` for the cache.
- `.../data/store/Symbols.kt`: symbol normalisation (`A-Z0-9.^=-`, 1..15).
- `.../data/store/WatchlistStore.kt`: the owner's watchlist: `watchlist.json` v1 (+ `watchlist.bak`), defaults XEQT.TO + MSFT, add/remove/move/rename/replace/reset, `validateAndAdd`, `addUnverified`, fsync'ed atomic writes, backup recovery, corrupt/newer-version handling, `flushIfDirty`, `EditResult` and `AddOutcome`.
- `.../data/repo/QuoteState.kt`: `QuoteState`, `QuoteStatus` (Fresh/Stale/Loading/Error) and `RepoConfig` (tunables).
- `.../data/repo/QuoteRepository.kt`: stale-while-revalidate quote repository: provider chain with fallback merge, cooldown/backoff, 4-way concurrency cap, network short-circuit, in-flight dedup, monotonic ages, clock skew, `forget`, cancellation safe.
- `.../demo/DemoData.kt`: throwaway M0 demo state (XEQT.TO in CAD, MSFT in USD, headlines) supplied by MainActivity, SandboxApp and Screenshots. Nothing else depends on it; delete with `DemoDataTest.kt` when M1/M2 land.

## app/ (Android)
- `app/build.gradle.kts`: Android module, shared srcDir + font res.
- `app/src/main/AndroidManifest.xml`: INTERNET + ACCESS_NETWORK_STATE only, portrait activity.
- `app/src/main/java/com/marketsboard/MainActivity.kt`: immersive, keep-screen-on, sets fonts, provides `LocalBackHook`, shows the root.
- `app/src/main/java/com/marketsboard/AppGraph.kt`: hand-built dependency graph (fetcher, log, providers, cache in `filesDir/cache`, `WatchlistStore` in `filesDir/watchlist.json`, `QuoteRepository`); takes a `File`, so it is JVM-testable.
- `app/src/main/java/com/marketsboard/MarketsBoardApplication.kt`: Application; builds `AppGraph` (crash-proof, null on failure). The UI does not use it until M2.
- `app/src/main/res/`: `values/themes.xml` (window bg #0A0A0B), `values/colors.xml`, `drawable/ic_launcher_fg.xml` (line chart glyph), `mipmap-anydpi-v26/ic_launcher.xml` (adaptive + monochrome).
- `app/src/test/kotlin/com/marketsboard/ui/FormatTest.kt`: Format rounding, sign/colour agreement, time-ago.
- `app/src/test/kotlin/com/marketsboard/ui/ThemeTest.kt`: Tabular font family, price font steps, QuoteRowUi derivation.
- `app/src/test/kotlin/com/marketsboard/demo/DemoDataTest.kt`: demo watchlist checks (deletable with DemoData).
- `app/src/test/kotlin/com/marketsboard/AppGraphTest.kt`: real wiring against fixtures (default watchlist quotes in CAD/USD, Stooq fallback, restart persistence, log hosts).
- `app/src/test/kotlin/com/marketsboard/data/TestSupport.kt`: `FakeClock`, `FakeHttp`, `ScriptedProvider`, `quoteOf`, fixture loader.
- `.../data/AuditFixesTest.kt` (M1 audit regressions: fallback merge, clocks, short-circuit, backoff, currencies, watchlist backup recovery, forget), `.../data/ModelsTest.kt`, `YahooProviderTest.kt`, `StooqProviderTest.kt`, `FileJsonCacheTest.kt`, `FetchLogTest.kt`, `WatchlistStoreTest.kt`, `QuoteRepositoryTest.kt`: data layer tests.
- `app/src/test/resources/fixtures/`: recorded-format JSON/CSV fixtures (Yahoo chart/search, Stooq CSV) written from knowledge of the real formats, NOT captured live.
- `app/src/test/kotlin/com/marketsboard/RepoHygieneTest.kt`: repo scan (no em-dashes, no raw colour literals outside Theme.kt, green/red only in changeColor, no model names, no Android imports in shared).

## sandbox/ (desktop JVM)
- `sandbox/build.gradle.kts`: Compose Multiplatform desktop, `screenshots` and `sandboxJar` tasks.
- `sandbox/run-sandbox.bat`: Windows launcher for the jar.
- `sandbox/src/main/kotlin/com/marketsboard/sandbox/Main.kt`: window entry point.
- `.../sandbox/SandboxApp.kt`: 9:16 head-unit frame running the real UI + control panel.
- `.../sandbox/Fonts.kt`: `loadDashFonts()` from classpath.
- `.../sandbox/FakeMarketDataProvider.kt`: deterministic fake provider (any symbol, currency inference, behaviours Normal/Slow/Offline/RateLimited/PartialFailure/BadData) for M2 sandbox and tests.
- `.../sandbox/Screenshots.kt`: headless PNG per tab to `sandbox/build/screenshots/`.
- `sandbox/src/test/kotlin/com/marketsboard/sandbox/RenderTest.kt`: headless render tests (long prices neither wrap nor clip at 440 dp, greyscale unless price moved, tabs render).
- `sandbox/src/test/kotlin/com/marketsboard/sandbox/JvmHttpFetcherTest.kt`: `JvmHttpFetcher` against a local `com.sun.net.httpserver.HttpServer` (lives here because that API is not on the Android unit-test classpath).
- `sandbox/src/test/kotlin/com/marketsboard/sandbox/FakeMarketDataProviderTest.kt`: fake provider behaviours.
