# Progress

## Status
M0 (scaffold, design system, tabbed shell, sandbox, docs, CI) is complete and audited. M1 (data layer + watchlist persistence) is implemented and its audit findings are fixed (see "M1 audit fixes"). M2 (watchlist-first Markets UI wired to the real data layer, editor, detail, refresh policy, sandbox controls) is implemented, pending its audit. Next: M2 audit, then M3.

## Done (M2)
- **Priority order held, nothing deferred**: (1) Markets list + refresh + states, (2) editor + add/search/replace, (3) detail with ranges.
- **Wiring (shared)**: `MarketsPresenter` (state + every action) over `MarketsData` (`Watchlist` + `QuotesSource` + provider). `QuotesSource` and `Watchlist` are small interfaces implemented by `QuoteRepository` and `WatchlistStore` (M1 behaviour unchanged, tests green). `UiMapper` is the pure mapping: row (display name override, TSX/NASDAQ labels from the WatchItem, native currency incl. GBX, decimals from `Quote.decimals` so FX is 4, status Loading/Fresh/Stale(age text)/Error(reason, last numbers kept dimmed), null previous close shows a dim "--"), header (green Live only when every held quote is Fresh and a market is open; grey "Markets closed" / "Loading"; amber "Delayed" / "Offline"; "Updated 12:41 PM ET" is the newest provider time in ITS exchange zone), clock-skew flag, detail model.
- **Refresh controller** (`refresh/RefreshController.kt`, tested with virtual time and a clock that follows it). Policy: only while the platform's `isActive` is true (Android: Activity onResume/onPause); immediate pass on start, resume and watchlist change; then a tick every 60 s where a symbol is due after 60 s when live (REGULAR/PRE/POST, or no quote yet, or the last refresh failed) and after 5 min when its market is closed (so all-closed means one pass per 5 min); never faster than 60 s per symbol automatically (a new symbol has no history and goes at once); no automatic pass while the repository cools down (429/5xx), the next tick after it retries; at most 4 concurrent (repository); `recomputeAges()` every 15 s while active; `flushIfDirty()` on every (re)start of the loop, off the main thread; completion is recorded only when a pass returns, so a cancelled pass (pause, list change) is retried; manual Refresh and per-row Retry are forced and still honour the cooldown (the repository answers "rate limited" without touching the network).
- **Markets screen**: hairline rows (min 84 dp), name + `SYMBOL  ·  EXCHANGE`, 1D sparkline with a dashed previous-close baseline at a fixed x so rows align, price in Numeral Light with the currency code, change abs + %. Skeleton blocks while Loading (no spinners); stale rows get an amber dot and "Updated 5 min ago"; failed refreshes keep the last numbers dimmed with an amber reason line and "Retry"; empty state "Add your first symbol"; thin amber banner "Check the date and time on this unit" on `clockSkew` or a TLS date failure; notice line for a read-only (newer version) or unsaved watchlist; header "Refresh" TextAction (shows "Refreshing" while a fetch runs).
- **Detail**: tap a row. Back affordance (and `LocalBackHook`/Esc), big price + change for the selected range, 1D/5D/1M/3M/1Y pills that fetch `quote(symbol, range)` with Loading (skeleton) and Error ("Try again") states, `LineChart` (1D has the previous-close baseline, the chart takes the change colour), `StatGrid` (Previous close, Market, Day high, Day low, Exchange, Currency, As of in exchange time, Source), Replace and "Remove from watchlist" (confirm dialog).
- **Editor**: "Edit" in the section header toggles edit mode: per row big up/down chevron buttons (48 dp), Rename (dialog, blank or the original name clears the override), Replace, Remove (confirm dialog, `MarketsData.remove` also forgets the cache), prominent "Add symbol". Removing the last item is allowed. **Add sheet** (full screen): live search debounced 400 ms through `MarketDataProvider.search` (results: name, symbol, exchange, type, "Added" badge), outcomes in plain language (Added, already in your list, invalid format, not found, list full, read-only, offline or rate limited with "Add SYMBOL anyway" which uses `addUnverified` and warns that prices appear once online), Done with a typed symbol and no results tries `validateAndAdd` directly, "Suggestions" (the 16 quick-add symbols, shown only while the field is empty, never on by default). **Replace** uses the same sheet with `MarketsData.validateAndReplace` (keeps the position, forgets the old symbol; the new item does not inherit the old display name).
- **Sandbox**: real presenter, controller and repository on `FakeMarketDataProvider` with a temp-dir store; restyled control panel (behaviour selector, latency slider, clock skew, app active, time speed x1/x10/x60, hotspot drop, reset to defaults). `SandboxTime` keeps a "true" clock for the fake market and a "device" clock that can be skewed.
- **Android**: `AppGraph` gained `MarketsData`; new `AppRuntime` (process-scoped controller + presenter), `MainActivity` renders the presenter's state and sets `active` from onResume/onPause. `DemoData` and `DemoDataTest` are deleted; the News tab shows `NewsComingSoon` instead of invented headlines. INTERNET permission was already present. State lives in stores, so process death only loses which screen was open.
- **Tests**: +98 in `app` (319), +11 in `sandbox` (59) on top of M1: 109 new, 378 in total; the two DemoData tests were deleted and RepoHygiene's green/red rule now allows `liveDotColor`.
- **Screenshots**: 17 PNGs (see SANDBOX.md), each looked at and iterated (round 1 fixes: sparkline alignment via a minimum price column width, Retry and edit-action alignment, one-line clock banner, shorter chart so the stat grid shows, BTC fixture scaled, more varied fake series; round 2 re-checked every screen).

## M2 decisions and judgement calls
- **Green "Live" dot**: the brief allows a green dot for fresh data, so `liveDotColor()` (Components.kt) is the one non-price use of green; amber for stale/offline, grey for closed/loading. RepoHygieneTest was updated to allow exactly that one extra line.
- **Header "Updated"** uses the newest provider time among held quotes and that quote's exchange zone (mixed exchanges show the newest one's zone). North American zones read ET/CT/MT/PT; others the JDK short name; unknown zone reads UTC. Older than today shows "Fri 4:00 PM ET" or "Nov 13, 4:00 PM ET"; with clock skew the full date form is used so no wrong "today" is implied.
- **Error row vs stale row**: a row with a quote whose last refresh FAILED is an Error row (numbers dimmed, reason + Retry); merely old data is a Stale row (amber dot + age). A failed refresh therefore shows as an error even 20 s after a success, which is accurate but louder than a silent retry.
- **Rate-limit text** uses the repository's live `cooldownRemainingMs()` when set, else the error's retry-after; the text is recomputed whenever state changes (not on a timer), so the minute count can lag until the next state change.
- **Closed-market polling** is per symbol (60 s tick, symbol due after 5 min when closed), which is equivalent to the "5 min when all closed" rule and keeps live symbols at one minute when markets are mixed.
- **Detail** polls nothing by itself: it fetches on open and on range change (the repository's `minRefetch` applies: 2 min for 5D, 30 min for longer); 1D is the list's own quote and is kept fresh by the controller. Price on the detail screen is the 1D quote's; change is that price against the selected range's reference close.
- **Replace** does not carry the old display name over (a name for a different instrument would be wrong).
- **Add sheet** stays open after a pick in add mode (so several symbols can be added), clears the field and shows "Added X"; Done, or any replace, closes it.
- Pausing cancels in-flight fetches (the repository restores any Loading state). Presenter edits run on `Dispatchers.IO`; the state flows are collected on the UI thread.
- LazyColumn rows are not keyed by symbol (a duplicate key would crash the screen; the store prevents duplicates but a UI must not depend on that).

## Unverified in M2 (fixture or sandbox only)
- Everything about live Yahoo behaviour (see the M1 list): the UI was only ever fed by the fake provider and fixtures. Search relevance, how Yahoo `fullExchangeName` strings look for other exchanges (unknown names pass through shortened to 14 chars), `marketState` values and session windows are assumptions.
- No device or emulator run: Android `MainActivity`/`AppRuntime` wiring compiled, linted and built into the APK, and the presenter/controller logic is unit tested, but the Activity lifecycle (onResume/onPause pause and resume), the on-screen keyboard behaviour of the add sheet, back button handling and touch feel were not seen on a device. The sandbox window itself was not opened interactively (headless rendering and `SandboxEnvTest` only).
- Text sizes were judged on 810 x 1440 headless renders (1.84x the 440 dp design width), not on the real 14.4" panel.

## Known issues (M2)
- The sandbox time speed-up also speeds up the fake market's own clock, so quotes move faster too (intended); the real refresh policy is unaffected.
- Detail charts of 5D to 1Y are not refreshed while the detail stays open (see decisions).
- The Settings tab is still the M0 placeholder (its "Refresh every 60 s" row is informational only).

## Done (M0)
- Gradle project mirroring Truck Dash: `:app`, `:sandbox`, shared source dir, wrapper 8.14.3, version catalog (no Room/KSP/lifecycle).
- Design system ported from Truck Dash (Theme, Components, Format, Charts without RouteMap, DesignScale, LocalBackHook), plus `Sparkline` (no axes, colour param, dashed previous-close baseline), `changeColor`, `SwitchRow`, `ValueRow`.
- Tabbed shell MARKETS / NEWS / SETTINGS with placeholder screens built from real components and `DemoData.kt`.
- Markets placeholder is watchlist-first per the owner's requirement: exactly XEQT.TO (CAD) and MSFT (USD), native currency beside the price, sparklines, green gain / red loss, inert "Edit" action in the section header.
- Android app: MainActivity (portrait, keep screen on, immersive, Geist, back hook), Application, manifest (INTERNET + ACCESS_NETWORK_STATE), line-chart adaptive + monochrome icon, dark theme, allowBackup=false.
- Sandbox: window (9:16 frame + minimal panel), headless screenshots (810x1440 per tab), `sandboxJar` (Windows + Linux Skia), `run-sandbox.bat`.
- Tests: 17 in `app` (Format, Theme, DemoData, RepoHygiene) and 5 in `sandbox` (RenderTest).
- CI workflow uploads APK, sandbox jar + bat, screenshots.

## M1 audit fixes
Independent audit of a78e4ab; each claim was checked in code, fixed and covered by a regression test (mostly `AuditFixesTest`, `JvmHttpFetcherTest`).
1. **Fallback no longer wipes the row.** `MarketDataProvider.supports(symbol, range)`; Stooq serves 1D and mapped symbols only. A fallback result (not from the first provider) is merged onto the held quote: price, provider time, high, low and market state are taken, previous close, series, names are kept; with nothing held the fallback quote is used as is. A fallback quote more than 1 day older than the held one, or more than 1 day in the future, is rejected (counts as a failure). `Quote.source` records the provider (stamped by the repository when blank). A fallback sourced quote is never throttled by `minRefetch`, so the primary is retried on the next cycle. Caveat: after a session roll the kept previous close is one session old.
2. **Clocks.** `Clock.monoMs()` (injectable) drives ages, minRefetch and cooldowns; wall time only for the persisted savedAt and skew. A cache entry written "in the future" is Stale with unknown age (`QuoteStatus.Stale(ageKnown=false)`), never Fresh, and never blocks a refresh. `QuoteState.clockSkew` is set when the device clock is behind the provider time (or the cache write time).
3. **Watchlist power-cut safety.** Temp file written through `FileOutputStream`, `fd.sync()`, then atomic rename; `watchlist.bak` keeps the previous good copy; load order is main, then backup, then defaults, so a zero-length or truncated file recovers from the backup (`LoadStatus.RecoveredFromBackup`); a non-empty broken file is still kept as `watchlist.corrupt-<ts>.json`. `flushIfDirty()` retries a failed write. A newer-version file shows its readable items read-only and is never overwritten. Note the backup is the PREVIOUS copy, so recovering from it can lose the very last edit.
4. **Bounded fetches.** `withTimeoutOrNull(20 s)` around the whole fetch and `disconnect()` on cancellation (callers are released at once; a blocked DNS lookup cannot be interrupted but its thread ends with the lookup). No query2 retry when the failure says the device is offline (unknown host, no route, network unreachable). After the first Network or ClockOrTls failure (or two timeouts) the not-yet-started symbols of the same `refresh` call are failed at once.
5. **Out-of-order guard removed** (in-flight dedup already serialises a key); only the fallback sanity rule above remains.
6. **Currency quirks.** `Currencies.normalize`: `GBp` becomes `GBX`, `ZAc` becomes `ZAC`, `ILA` stays; other codes are upper-cased. Used by the Yahoo parser and the watchlist. `Currencies.isMinorUnit` / `majorOf` for the UI.
7. **Backoff.** One streak increment per cooldown window (4 concurrent 429s give a 30 s cooldown, not 240 s). A 5xx with a JSON error body is `Http(5xx)` and backs off. Stooq's 200 "Exceeded the daily hits limit" body is `RateLimited(1 h)`; Retry-After is honoured up to 1 h (`RepoConfig.maxRetryAfterMs`), exponential backoff stays capped at 15 min. New `ErrorKind.ClockOrTls` for TLS certificate date failures.
8. **Derived marketState** (only when Yahoo omits it) needs the device clock inside today's regular window AND a last trade at most 20 min old, else CLOSED; a device clock behind the last trade gives unknown (null). Holidays are only detected heuristically (a stale last trade reads as CLOSED).
9. **Quote additions**: `source`, `sessionStartMs`, `sessionEndMs`, `exchangeTimezoneName`, `priceHint` plus `Quote.decimals` (hint, else 4 for currency pairs, else 2). `QuoteRepository.forget(symbol)` drops states, cache entries and series for all ranges (and a fetch already running will not bring it back); `AppGraph.removeFromWatchlist` / `replaceInWatchlist` are the documented hooks for the UI.
10. `refreshing` is now a lock-guarded counter (stays true while any fetch is active). Stooq rows are parsed by header name and tolerate a missing trailing field. Tests added for plain-text 429, a response without a `timestamp` key and a `GBp` payload.

Decisions recorded: no `v8/spark` batch endpoint (unverifiable here; a future option for fewer requests). No TMX Money or Bank of Canada providers yet: see Next ideas.

## Done (M1: data layer, no UI change)
- Everything under `shared/.../data/{model,net,provider,repo,store}`, pure Kotlin on Android API 26+ and desktop JVM.
- **Models**: Instrument, WatchItem, Quote (provider time), ChartRange, `FetchResult` Ok/Err with typed `ErrorKind`; `QuoteValidator` rejects NaN/Inf/non-positive/absurd (> 1e9) price and missing provider time, drops bad series points keeping order.
- **HTTP**: `JvmHttpFetcher` (8 s connect, 10 s read, 20 s deadline around the WHOLE fetch with disconnect on cancel, gzip, 3 redirects without scheme downgrade, 2 MB cap on decoded bytes, error bodies, Retry-After seconds or date, typed errors, never throws).
- **Providers**: `YahooChartProvider` (chart + search, query2 host fallback on network/timeout/5xx) and `StooqProvider` + `YahooToStooq` mapping (`supports()` is false when there is no mapping, so "no fallback available" is graceful).
- **Cache + repository**: `FileJsonCache` (atomic, TTL, size cap, corrupt tolerant); `QuoteRepository` with stale-while-revalidate, provider chain, per-provider cooldown/backoff, 4-way cap, in-flight dedup, fallback merge, monotonic clock, cancellation safety; `FetchLog` ring buffer fed by `LoggingHttpFetcher`.
- **WatchlistStore**: `watchlist.json` v1, defaults exactly XEQT.TO (CAD, TSX) and MSFT (USD, NASDAQ), add/remove/move/rename/replace/reset, normalisation, max 30, `validateAndAdd` + `addUnverified` ("add anyway when offline"), fsync'ed atomic writes with a `watchlist.bak` previous copy, corrupt file quarantined as `watchlist.corrupt-<ts>.json` (max 5 kept) and recovered from the backup, newer-version file left untouched (its readable items shown read-only), unreadable file read-only, write failure keeps the edit in memory and is retried (`flushIfDirty`, `persistError`).
- **Android wiring**: `AppGraph(File)` + `MarketsBoardApplication` builds it (UI not wired until M2).
- **Sandbox**: `FakeMarketDataProvider` (deterministic, stable per symbol, six behaviours switchable from code, no UI yet).
- **Tests**: 223 in `app` (17 from M0) and 48 in `sandbox` (5 from M0), 249 new in total. Existing UI screenshots are byte-identical to M0.

## M1 decisions and judgement calls
- **JSON**: kotlinx.serialization-json 1.7.3 used through its `JsonElement` tree API with small defensive accessors (`provider/JsonSupport.kt`), no `@Serializable` classes and therefore no serialization compiler plugin (nothing to generate; wrong types and nulls in third-party JSON become null instead of exceptions). Only the runtime dependency was added, to both modules via the catalog.
- **Previous close**: D1 uses the previous SESSION close (`chartPreviousClose`, else `previousClose`); longer ranges use `chartPreviousClose` (close before the range start, so the change is the move over the displayed range), else the first series point. Stooq has no previous close (its Open is not one); a fallback result is merged onto the held quote (see audit fixes), but with nothing held the quote has `prevClose = null` and no series and the UI must tolerate that.
- **Currency is never guessed by providers.** Yahoo `currency` or blank; Stooq blank. The repository fills blank currency/exchange/name from the WatchItem (`hints`). Quick-add "add anyway" stores currency and exchange blank until the first good quote.
- **State keys**: `QuoteRepository.keyFor` is the plain symbol for D1 and `SYMBOL@5D` etc for other ranges, so a detail chart never overwrites the list row.
- **Cooldown is per provider, repository wide** (shared by all symbols, also applies to forced refreshes). 429 and 5xx trigger it (Retry-After honoured up to 1 h; else 30 s, 60 s... capped at 15 min; success resets; one failing window counts once). Network, timeout, 4xx and parse errors never trigger a cooldown (the user presses refresh when the hotspot returns).
- **Fallback rule**: any error moves to the next provider that `supports()` the symbol, which implements "NotFound is not retried on the fallback unless the mapping exists". The reported error is the first provider's.
- **Freshness is tracked by fetch time, not provider time** (provider `asOf` stops moving while a market is closed). Defaults: Stale after 5 min (1D), 15 min (5D), 6 h (longer); a non-forced refresh is skipped under 30 s (1D). Cache entries older than 7 days are discarded. `recomputeAges()` must be called periodically by the M2 refresh loop so Fresh turns Stale without a refresh.
- **Clocks**: in-process ages, minimum refetch and cooldowns use a MONOTONIC clock (`Clock.monoMs`); the wall clock is used only for persisted times (cache savedAt, log) and skew detection. Quote times always come from the provider.
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
- With nothing held, a Stooq fallback gives a price only (no change, no sparkline, currency via the hint); the Markets UI must show that gracefully. Stooq has no TSX coverage, so XEQT.TO has NO fallback today (expected; the UI must handle it).
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
- **M2 refresh contract** (implemented in M2, kept for reference; from the M1 audit): poll at most every 60 s, only the symbols visible on screen, at most 4 concurrent (the repository enforces 4), stop the batch on a network failure (the repository short-circuits), call `QuoteRepository.recomputeAges()` on each tick, call `WatchlistStore.flushIfDirty()` on resume, use `AppGraph.removeFromWatchlist` / `replaceInWatchlist` so cache and state are dropped, show `QuoteState.clockSkew` and `ErrorKind.ClockOrTls` as "check the date and time", use `Quote.decimals`, `Currencies.isMinorUnit` (GBX/ZAC/ILA are pence/cents/agorot, never show them as pounds/rand/shekels) and the session fields for market-hours awareness.
- **M3** News: RSS provider + parser, list/detail, dedupe, time-ago, cache, briefing text builder.
- **M4** Drive safety: GPS speed gate, glance mode, passenger mode (the Settings switch is local state until then), TTS briefing behind an interface.
- **M5** Settings and polish: feed editor, diagnostics, connectivity handling, release artifacts, whole-repo audit.

## Next ideas
- Fallback providers for the owner's two defaults, since Stooq cannot serve XEQT.TO: TMX Money (TSX quotes) for XEQT.TO and the Bank of Canada Valet API for USD/CAD. Not built now; the provider interface and `supports(symbol, range)` already allow them.
- `v8/spark` batch quotes (one request for many symbols) if request volume ever matters; unverifiable from the build container.

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
