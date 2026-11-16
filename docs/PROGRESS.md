# Progress

## Status
M0 (scaffold, design system, tabbed shell, sandbox, docs, CI) is complete and audited. M1 (data layer + watchlist persistence) is implemented and awaiting audit. Next: M1 audit fixes, then M2.

## Done (M0)
- Gradle project mirroring Truck Dash: `:app`, `:sandbox`, shared source dir, wrapper 8.14.3, version catalog (no Room/KSP/lifecycle).
- Design system ported from Truck Dash (Theme, Components, Format, Charts without RouteMap, DesignScale, LocalBackHook), plus `Sparkline` (no axes, colour param, dashed previous-close baseline), `changeColor`, `SwitchRow`, `ValueRow`.
- Tabbed shell MARKETS / NEWS / SETTINGS with placeholder screens built from real components and `DemoData.kt`.
- Markets placeholder is watchlist-first per the owner's requirement: exactly XEQT.TO (CAD) and MSFT (USD), native currency beside the price, sparklines, green gain / red loss, inert "Edit" action in the section header.
- Android app: MainActivity (portrait, keep screen on, immersive, Geist, back hook), Application, manifest (INTERNET + ACCESS_NETWORK_STATE), line-chart adaptive + monochrome icon, dark theme, allowBackup=false.
- Sandbox: window (9:16 frame + minimal panel), headless screenshots (810x1440 per tab), `sandboxJar` (Windows + Linux Skia), `run-sandbox.bat`.
- Tests: 17 in `app` (Format, Theme, DemoData, RepoHygiene) and 5 in `sandbox` (RenderTest).
- CI workflow uploads APK, sandbox jar + bat, screenshots.

## Done (M1: data layer, no UI change)
- Everything under `shared/.../data/{model,net,provider,repo,store}`, pure Kotlin on Android API 26+ and desktop JVM.
- **Models**: Instrument, WatchItem, Quote (provider time), ChartRange, `FetchResult` Ok/Err with typed `ErrorKind`; `QuoteValidator` rejects NaN/Inf/non-positive/absurd (> 1e9) price and missing provider time, drops bad series points keeping order.
- **HTTP**: `JvmHttpFetcher` (8 s connect, 10 s read, 25 s overall deadline, gzip, 3 redirects without scheme downgrade, 2 MB cap on decoded bytes, error bodies, Retry-After seconds or date, typed errors, never throws).
- **Providers**: `YahooChartProvider` (chart + search, query2 host fallback on network/timeout/5xx) and `StooqProvider` + `YahooToStooq` mapping (`supports()` is false when there is no mapping, so "no fallback available" is graceful).
- **Cache + repository**: `FileJsonCache` (atomic, TTL, size cap, corrupt tolerant); `QuoteRepository` with stale-while-revalidate, provider chain, per-provider cooldown/backoff, 4-way cap, in-flight dedup, out-of-order guard, cancellation safety, injectable clock; `FetchLog` ring buffer fed by `LoggingHttpFetcher`.
- **WatchlistStore**: `watchlist.json` v1, defaults exactly XEQT.TO (CAD, TSX) and MSFT (USD, NASDAQ), add/remove/move/rename/replace/reset, normalisation, max 30, `validateAndAdd` + `addUnverified` ("add anyway when offline"), atomic writes, corrupt file quarantined as `watchlist.corrupt-<ts>.json` (max 5 kept), newer-version file left untouched (read-only defaults), unreadable file read-only, write failure keeps the edit in memory and retries on the next edit (`persistError`).
- **Android wiring**: `AppGraph(File)` + `MarketsBoardApplication` builds it (UI not wired until M2).
- **Sandbox**: `FakeMarketDataProvider` (deterministic, stable per symbol, six behaviours switchable from code, no UI yet).
- **Tests**: 177 in `app` (22 from M0) and 43 in `sandbox` (5 from M0), 198 new. Existing UI screenshots are byte-identical to M0.

## M1 decisions and judgement calls
- **JSON**: kotlinx.serialization-json 1.7.3 used through its `JsonElement` tree API with small defensive accessors (`provider/JsonSupport.kt`), no `@Serializable` classes and therefore no serialization compiler plugin (nothing to generate; wrong types and nulls in third-party JSON become null instead of exceptions). Only the runtime dependency was added, to both modules via the catalog.
- **Previous close**: D1 uses the previous SESSION close (`chartPreviousClose`, else `previousClose`); longer ranges use `chartPreviousClose` (close before the range start, so the change is the move over the displayed range), else the first series point. Stooq has no previous close (its Open is not one), so fallback quotes have `prevClose = null` and no series: the UI must tolerate that.
- **Currency is never guessed by providers.** Yahoo `currency` or blank; Stooq blank. The repository fills blank currency/exchange/name from the WatchItem (`hints`). Quick-add "add anyway" stores currency and exchange blank until the first good quote.
- **State keys**: `QuoteRepository.keyFor` is the plain symbol for D1 and `SYMBOL@5D` etc for other ranges, so a detail chart never overwrites the list row.
- **Cooldown is per provider, repository wide** (shared by all symbols, also applies to forced refreshes). 429 and 5xx trigger it (Retry-After honoured; else 30 s, 60 s... capped at 15 min; success resets). Network, timeout, 4xx and parse errors never trigger a cooldown (the user presses refresh when the hotspot returns).
- **Fallback rule**: any error moves to the next provider that `supports()` the symbol, which implements "NotFound is not retried on the fallback unless the mapping exists". The reported error is the first provider's.
- **Freshness is tracked by fetch time, not provider time** (provider `asOf` stops moving while a market is closed). Defaults: Stale after 5 min (1D), 15 min (5D), 6 h (longer); a non-forced refresh is skipped under 30 s (1D). Cache entries older than 7 days are discarded. `recomputeAges()` must be called periodically by the M2 refresh loop so Fresh turns Stale without a refresh.
- **Wall clock use**: ages, TTL and backoff use the device clock (needed across restarts); a clock that jumps backwards just yields age 0. Quote times always come from the provider; Yahoo `marketState` is derived from `currentTradingPeriod` with the device clock only when Yahoo omits it (a hint, best effort).
- **WatchlistStore threading**: synchronous, lock-guarded file IO of a few hundred bytes; M2 should call it from `Dispatchers.IO`. Unknown JSON fields are ignored on read and not preserved on rewrite. A file with items that fail to parse is copied aside (not renamed) and the good items are kept.
- **Exchange display**: Yahoo's `fullExchangeName` is "Toronto" / "NasdaqGS", not "TSX" / "NASDAQ". `WatchItem.exchange` (defaults, or filled at add time) is only used when the quote has none, so M2 should decide which to show (suggest the WatchItem's for the two defaults).
- `JvmHttpFetcherTest` lives in `:sandbox` because `com.sun.net.httpserver` is not on the Android unit-test classpath; everything else is in `app/src/test`.

## Unverified against the live APIs (fixture-tested only)
The build container cannot reach Yahoo or Stooq. Fixtures in `app/src/test/resources/fixtures/` were written from knowledge of the formats, not captured. Therefore UNVERIFIED live:
- Yahoo chart: that `query1`/`query2` answer without a crumb or cookie for a browser-like User-Agent, field names and null patterns (`chartPreviousClose`, `previousClose`, `regularMarketTime`, `fullExchangeName`), that `previousClose` is absent for most chart responses, `currentTradingPeriod` semantics on weekends, and the exact error body for unknown symbols and throttling (429 vs 200 with an HTML body, both handled as errors).
- Yahoo search: field names (`longname`, `shortname`, `exchDisp`, `quoteType`) and that unauthenticated search still works.
- Stooq: CSV column order for `f=sd2t2ohlcv`, the `N/D` convention, the Warsaw time zone assumption for the timestamp, whether the host is reachable from the head unit, and every mapping in `YahooToStooq` (index, FX and futures names such as `^spx`, `usdcad`, `cl.f`).
- `JvmHttpFetcher` was tested against a local HTTP server only (no TLS, no real proxy, no Android runtime). Redirect scheme-upgrade (http to https) is covered only by the downgrade refusal test.
- Nothing ran on a device or emulator; `AppGraph` construction in the real `Application` is untested beyond the JVM test of the graph itself.

## Known issues (M1)
- Stooq fallback gives a price only (no change, no sparkline, no currency except via the hint); the Markets UI must show that gracefully.
- Quotes of different providers are not merged: a fallback result replaces a richer cached Yahoo quote.
- Not yet used by any UI: the refresh loop, market-hours awareness, search UI and diagnostics screen arrive in M2/M5.
- The two defaults show Yahoo's exchange strings if the UI prefers `Quote.exchange` (see decisions).

## M0 audit fixes
- `Tabular` is a getter carrying `DashFonts.family` (numerals were falling back to the platform font).
- Price column no longer fixed width: name column takes the leftover width, numeral steps down 24/22/20/18 sp by length, unit never wraps; render test at 440 dp proves it.
- Text, sign and colour come from one BigDecimal rounding step (no "-0.00", no coloured zero).
- Switch uses white track / dark thumb; green and red are used only by `changeColor`; destructive red (Danger pill, dialog danger) removed; error colour is amber.
- Screens take UI models (`QuoteRowUi`, `HeadlineUi`, `*UiState`); `DemoData` (package `demo`) is supplied by callers only.
- Immersive mode re-applied on window focus. Readable secondary text uses `Dash.dim`; `Dash.faint` remains only for the decorative chevron and disabled/decorative elements. `Label` default is now dim.
- Docs/CI: no macOS claim, no model name in CLAUDE.md, lint report and screenshots uploaded (screenshots fail if missing), `Locale.US` in charts. Repo hygiene test added.

## Next
- **M1 audit**: fix valid findings, add a regression test each.
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
- Live providers cannot be reached from the build container; all provider work is fixture-tested only (see the M1 unverified list).
- Launcher icon and real-device rendering were not seen on a device (only headless Skia screenshots).
- The sandbox window itself was built but not opened interactively (headless screenshots only).
