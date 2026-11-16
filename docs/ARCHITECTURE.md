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
- `.../ui/Components.kt`: Label, ScreenTitle, Hairline, Dot, DashCard, Numeral, StatGrid, PillButton, TextAction, SectionHeader, SwitchRow, ValueRow, DashDialog, Skeleton, `changeColor` and `liveDotColor` (the only two users of green/red).
- `.../ui/Format.kt`: pure formatting (formatPrice, formatSigned, formatPercent, changeSign, formatTimeAgo), typographic minus; one BigDecimal rounding step shared by text, sign and colour.
- `.../ui/DesignScale.kt`: `DesignScale` (440 dp design width) and `LocalBackHook`.
- `.../ui/MarketsBoardRoot.kt`: `MbTab` enum and the tabbed shell with hairline bottom bar; takes `MarketsUiState` + `MarketsActions` + `NewsUiState` from the caller.
- `.../ui/charts/Charts.kt`: `LineChart` (optional dashed baseline), `BarChart`, `Sparkline`, `downsample`.
- `.../ui/markets/MarketsUi.kt`: UI models (`QuoteRowUi` + `RowStatus`, `HeaderUi`, `DetailUi`, `AddUi`, `SearchRowUi`, `MarketsUiState`) and the `MarketsActions` interface (every user action; `None` default).
- `.../ui/markets/UiMapper.kt`: pure mapping from `QuoteState` to rows, header ("Live"/"Delayed"/"Offline"/"Markets closed"), clock-skew flag and the detail model.
- `.../ui/markets/MarketsPresenter.kt`: `StateFlow<MarketsUiState>` from watchlist + quotes + navigation; implements all actions (edit mode, move, rename, remove, add/replace flow with 400 ms debounced search, detail range loading, back order).
- `.../ui/markets/Suggestions.kt`: the owner's quick-add list (never on by default).
- `.../ui/markets/MarketsScreen.kt`: Markets tab: header (title, Refresh, live dot + "Updated 12:41 PM ET"), clock banner, watchlist, edit mode, empty state, overlays (detail, add sheet, confirm and rename dialogs).
- `.../ui/markets/QuoteRow.kt`: the 84 dp row (skeleton, stale/error status band with Retry, dimmed numbers), `EditRow`, `ArrowButton`, `BackChevron`, `priceFontSizeSp`.
- `.../ui/markets/DetailScreen.kt`: instrument detail (back, price, change, 1D/5D/1M/3M/1Y pills, line chart with previous-close baseline, `StatGrid`, Replace, Remove).
- `.../ui/markets/AddSheet.kt`: full-screen add / replace sheet (search field, outcome message, "Add anyway", results with "Added" badge, suggestions).
- `.../core/Texts.kt`: `ErrorText` (plain-language reasons, rate-limit countdown), `AddText` (outcome messages), `ExchangeLabels` (Toronto to TSX, NasdaqGS to NASDAQ), `TypeLabels`, `ExchangeTime` (exchange-zone time text, ET/CT/MT/PT labels).
- `.../core/MarketPhase.kt`: REGULAR/PRE/POST vs closed from `marketState` or the session window; which symbols poll at the live rate.
- `.../refresh/RefreshController.kt`: the refresh policy (60 s live / 5 min closed, immediate on start/resume/list change, 15 s age recompute, pause when inactive, cooldown aware, never under 60 s per symbol), `Refresher`, `RefreshConfig`.
- `.../data/MarketsData.kt`: watchlist + quotes + provider bundle; remove/replace/reset that also `forget`, `validateAndReplace`, `replaceUnverified`.
- `.../data/repo/QuotesSource.kt`: interface of what UI and controller need from `QuoteRepository` (fakeable).
- `.../data/store/Watchlist.kt`: interface implemented by `WatchlistStore` (fakeable).
- `.../ui/news/NewsUi.kt`: `HeadlineUi`, `NewsUiState` and `NewsComingSoon` (honest placeholder until M3).
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

## app/ (Android)
- `app/build.gradle.kts`: Android module, shared srcDir + font res.
- `app/src/main/AndroidManifest.xml`: INTERNET + ACCESS_NETWORK_STATE only, portrait activity.
- `app/src/main/java/com/marketsboard/MainActivity.kt`: immersive, keep-screen-on, sets fonts, provides `LocalBackHook`, shows the root with the presenter's state, sets `active` from onResume/onPause.
- `app/src/main/java/com/marketsboard/AppGraph.kt`: hand-built dependency graph (fetcher, log, providers, cache in `filesDir/cache`, `WatchlistStore` in `filesDir/watchlist.json`, `QuoteRepository`, `MarketsData`); takes a `File`, so it is JVM-testable.
- `app/src/main/java/com/marketsboard/AppRuntime.kt`: process-scoped `RefreshController` + `MarketsPresenter` on top of the graph; `active` is flipped by the Activity.
- `app/src/main/java/com/marketsboard/MarketsBoardApplication.kt`: Application; builds `AppGraph` and `AppRuntime` (crash-proof, null on failure).
- `app/src/main/res/`: `values/themes.xml` (window bg #0A0A0B), `values/colors.xml`, `drawable/ic_launcher_fg.xml` (line chart glyph), `mipmap-anydpi-v26/ic_launcher.xml` (adaptive + monochrome).
- `app/src/test/kotlin/com/marketsboard/ui/FormatTest.kt`: Format rounding, sign/colour agreement, time-ago.
- `app/src/test/kotlin/com/marketsboard/ui/ThemeTest.kt`: Tabular font family, price font steps, QuoteRowUi derivation.
- `app/src/test/kotlin/com/marketsboard/AppGraphTest.kt`: real wiring against fixtures (default watchlist quotes in CAD/USD, Stooq fallback, restart persistence, log hosts).
- `app/src/test/kotlin/com/marketsboard/UiTestSupport.kt`: `VirtualClock` (follows test scheduler time), `FakeQuotesSource` (records calls), `FakeProvider` (scripted quote/search).
- `app/src/test/kotlin/com/marketsboard/ui/UiMapperTest.kt`, `ui/MarketsPresenterTest.kt`, `refresh/RefreshControllerTest.kt`, `core/CoreTextsTest.kt`: M2 tests (row/header/detail mapping, editor and add/replace flow, search debounce, refresh policy with a fake clock, wording and exchange-time formatting).
- `app/src/test/kotlin/com/marketsboard/data/TestSupport.kt`: `FakeClock`, `FakeHttp`, `ScriptedProvider`, `quoteOf`, fixture loader.
- `.../data/AuditFixesTest.kt` (M1 audit regressions: fallback merge, clocks, short-circuit, backoff, currencies, watchlist backup recovery, forget), `.../data/ModelsTest.kt`, `YahooProviderTest.kt`, `StooqProviderTest.kt`, `FileJsonCacheTest.kt`, `FetchLogTest.kt`, `WatchlistStoreTest.kt`, `QuoteRepositoryTest.kt`: data layer tests.
- `app/src/test/resources/fixtures/`: recorded-format JSON/CSV fixtures (Yahoo chart/search, Stooq CSV) written from knowledge of the real formats, NOT captured live.
- `app/src/test/kotlin/com/marketsboard/RepoHygieneTest.kt`: repo scan (no em-dashes, no raw colour literals outside Theme.kt, green/red only in changeColor, no model names, no Android imports in shared).

## sandbox/ (desktop JVM)
- `sandbox/build.gradle.kts`: Compose Multiplatform desktop, `screenshots` and `sandboxJar` tasks.
- `sandbox/run-sandbox.bat`: Windows launcher for the jar.
- `sandbox/src/main/kotlin/com/marketsboard/sandbox/Main.kt`: window entry point.
- `.../sandbox/SandboxApp.kt`: 9:16 head-unit frame running the real presenter and UI + control panel.
- `.../sandbox/SandboxEnv.kt`: real presenter, controller, repository and temp-dir watchlist on the fake provider; `SandboxTime` (speed-up, device clock skew), hotspot drop, watchlist reset.
- `.../sandbox/ControlPanel.kt`: panel in the app's own style (behaviour, latency, clock skew, app active, time speed, hotspot drop, reset).
- `.../sandbox/ScreenshotFixtures.kt`: deterministic Markets states (fake provider at a fixed time through `UiMapper`) for screenshots and render tests.
- `.../sandbox/Fonts.kt`: `loadDashFonts()` from classpath.
- `.../sandbox/FakeMarketDataProvider.kt`: deterministic fake provider (any symbol, currency inference, exchange time zones, latency, behaviours Normal/Slow/Offline/RateLimited/PartialFailure/BadData, 27-entry search catalogue).
- `.../sandbox/Screenshots.kt`: headless PNGs (17 Markets, detail, editor, add, empty and tab screens) to `sandbox/build/screenshots/`.
- `sandbox/src/test/kotlin/com/marketsboard/sandbox/RenderTest.kt`: headless render tests (long prices neither wrap nor clip at 440 dp, greyscale unless price moved, tabs render).
- `sandbox/src/test/kotlin/com/marketsboard/sandbox/MarketsRenderTest.kt`: 440 dp layout checks (ellipsis, 6-digit and 4-decimal prices, gutters, every screen) and the palette test (only green/red/amber/greyscale).
- `sandbox/src/test/kotlin/com/marketsboard/sandbox/SandboxEnvTest.kt`: presenter + controller + repository + fake provider end to end (real time).
- `sandbox/src/test/kotlin/com/marketsboard/sandbox/JvmHttpFetcherTest.kt`: `JvmHttpFetcher` against a local `com.sun.net.httpserver.HttpServer` (lives here because that API is not on the Android unit-test classpath).
- `sandbox/src/test/kotlin/com/marketsboard/sandbox/FakeMarketDataProviderTest.kt`: fake provider behaviours.
