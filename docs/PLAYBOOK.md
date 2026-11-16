# Build playbook (read this first, it replaces any prior context)

Distilled lessons from building the sister app **Truck Dash** (`/home/user/truck-dash`, read-only reference, do not modify it).
Everything an agent needs to know about environment, architecture, design and process is here.

## 1. What this app is
**Markets Board**: a separate Android app for a 14.4" PORTRAIT head unit (2015 GMC Sierra, owner in **Canada**).
Glanceable market dashboard + news, internet via the owner's iPhone hotspot (Wi-Fi) or the unit's own LTE.
Owner reads it **while parked**; while driving it must degrade to a minimal glance view (and later audio briefing).
It is its own app so a network failure can never touch the Truck Dash speedometer.
Owner is Canadian: prefer TSX, CAD, USD/CAD, WTI/Brent (trucking), gold, BTC/ETH, plus US indices. Metric units everywhere
(km/h, km, litres, kPa, degC). Currency shown with symbol and code where ambiguous.

## 2. Environment facts (cloud container)
- Android SDK: `export ANDROID_HOME=/home/user/android-sdk` (platform 35, build-tools 35.0.0 installed). JDK 21. System Gradle 8.14.3.
- Gradle wrapper must be generated per project: `gradle wrapper --gradle-version 8.14.3 --no-daemon`. `local.properties` has `sdk.dir=/home/user/android-sdk` (gitignored).
- Always run gradle with `--no-daemon -q` and a long Bash `timeout` (up to 600000 ms), e.g. `./gradlew testDebugUnitTest :sandbox:test assembleDebug lintDebug --no-daemon -q 2>&1 | grep -v "JAVA_TOOL_OPTIONS\|SDK XML" | tail -30`.
- Maven Central sometimes answers HTTP 429: just rerun the command once. `dl.google.com` and `maven.google.com` are already allowed.
- **Reachable**: github.com (git), raw.githubusercontent.com, registry.npmjs.org, pypi, Maven. **NOT reachable**: finance.yahoo.com, stooq.com, cbc.ca and other market/news hosts (curl returns 000).
  So **live API calls cannot be tested here**. Build providers against realistic recorded fixtures, parse defensively, and surface errors in an in-app diagnostics screen. Never fake "it works live"; say clearly what was only fixture-tested.
- Do NOT call the environment-update tools asking to allow hosts (needs a human approval card, owner is asleep).
- Bash blocks `sleep`; to wait use an `until` loop with `timeout`. No interactive commands.
- Headless UI screenshots work (Skia software raster via `ImageComposeScene`). Real window capture: `xvfb-run -a -s "-screen 0 1280x1000x24" script` then ImageMagick `import -window root out.png`. Skiko prints a harmless "Cannot create Linux GL context" warning.
- Read PNGs with the Read tool to LOOK at your UI. Do this after every UI change, for every screen; fix what looks wrong.
- Git: repo `willfox882/markets-board`, clone at `/home/user/markets-board`, branch `main`. Push with `git -c push.negotiate=true push origin main`.
  Commit message trailer (exactly, last lines):
  `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` then `Claude-Session: https://claude.ai/code/session_018sQ45MNG5zi6XhvhVziVLb`.
  Never include a model name anywhere else in the repo. Do not create PRs. Cannot create new repos.
- Use the scratchpad dir for temp files. Deliver files to the owner with SendUserFile only when asked by the orchestrator.

## 3. Architecture pattern that worked (copy it)
Two Gradle modules + one plain shared source dir (NOT the KMP plugin):
- `:app` Android (Compose, minSdk 26, targetSdk 34, compileSdk 35, Kotlin 2.1.0, AGP 8.9.1, KSP only if Room is needed, Compose BOM 2025.01.01).
- `:sandbox` desktop JVM (Compose Multiplatform 1.7.3, `kotlin.jvm` plugin) that runs the REAL UI against a simulated data source. Provides: a window (left: 9:16 phone-shaped frame, right: control panel), a headless `screenshots` task, a cross-platform fat jar (`sandboxJar`, bundles both `compose.desktop.windows_x64` and `currentOs` so `java -jar` works on Windows), a `.bat` launcher, and JUnit tests.
- `shared/src/main/kotlin/com/<pkg>/` is compiled by BOTH modules via `srcDir`. **No `android.*`, Room, `androidx.lifecycle`, `androidx.activity` imports there.** Android-only things are injected (e.g. a `LocalBackHook` composition local, repository interfaces, font loading).
- Shared contains: pure-Kotlin logic (`core`/`engine`, unit tested), plain models, repository INTERFACES, stateless Compose screens + small `*Presenter` classes (hold `StateFlow`s, take a `CoroutineScope`), design system. Platform modules provide the implementations.
- Use `collectAsState` in shared UI. Java APIs allowed in shared must exist on Android API 26+ AND desktop JVM (`java.time`, `HttpURLConnection`, `java.io.File`, `javax.xml.parsers`). For JSON use kotlinx.serialization (plugin + dependency work on both) or hand parsing; `org.json` is NOT on the desktop JVM.
- Version catalog `gradle/libs.versions.toml` (copy versions from `/home/user/truck-dash/gradle/libs.versions.toml`); `RepositoriesMode.FAIL_ON_PROJECT_REPOS`; `.gitignore` build outputs, `local.properties`, `*.apk`.
- Android build needs: `kotlin { compilerOptions { jvmTarget = JVM_17 } }` + Java 17 compat in BOTH modules (JDK 21 is installed); `sourceSets["main"].java.srcDir("../shared/src/main/kotlin")` and `.res.srcDir("../shared/res")`; sandbox: `sourceSets["main"].kotlin.srcDir(...)` and `sourceSets["main"].resources.srcDir("../shared/res")`.
- Reference implementation to copy structure from (read only): `/home/user/truck-dash/{settings.gradle.kts,build.gradle.kts,gradle.properties,gradle/libs.versions.toml,app/build.gradle.kts,sandbox/build.gradle.kts,sandbox/src/main/kotlin/com/truckdash/sandbox/{Main.kt,Fonts.kt,Screenshots.kt,SandboxApp.kt}}`.
- Compose gotchas hit before: `Text(...)` has NO `fontFeatureSettings` parameter (pass `style = TextStyle(fontFeatureSettings = "tnum")`); `Modifier.height(IntrinsicSize.Min)` rows are fine with Text only; `LinearProgressIndicator` shows a stray end dot unless `drawStopIndicator = {}`; desktop needs `loadDashFonts()` before UI, Android sets `DashFonts.family` from `R.font.*` in `onCreate`; `rememberSaveable` works but keep state simple; avoid `Dispatchers.Main` in shared code.
- `DesignScale`: the root lays everything out as if the screen were 440 dp wide regardless of real density (head unit density unknown). Copy `DesignScale` from Truck Dash `TruckDashRoot.kt`.

## 4. Design system (hard rules from the owner)
Premium, Apple / Wealthsimple feel. **Copy, do not reinvent**: from `/home/user/truck-dash/shared/src/main/kotlin/com/truckdash/ui/` take `Theme.kt`, `Components.kt`, `Format.kt`, `charts/Charts.kt` and the fonts in `/home/user/truck-dash/shared/res/font/geist_*.ttf` (+ `docs/licenses/OFL-Geist.txt`). Change the package name only.
- Matte near-black bg #0A0A0B, surfaces #131315 / #1C1C1F, hairlines #28282C. Hairline-separated rows instead of stacked coloured cards. No navy, no glow, no gradients on surfaces.
- Typeface **Geist** (Light for big numerals, Regular body, Medium labels). Labels are 11sp Medium UPPERCASE letterSpacing 1.3sp. Tabular figures for live numbers.
- **Colour is meaning, not decoration.** White is the primary accent. For a markets app: gains and losses need colour; use the existing muted `Dash.green` (#55D08A) and `Dash.red` (#EF6A64) ONLY for price change, stale/offline uses `Dash.amber`. Everything else greyscale. Add no other hues.
- **No em-dashes anywhere** (UI strings, docs, code comments, commit messages). Use commas, colons, the middle dot, or a hyphen.
- Portrait only, big touch targets (head unit), dark only.
- Safety rule: dense UI is hidden above 5 km/h unless Passenger mode; a minimal glance view stays.
- Always check every screen visually via `:sandbox:screenshots` and fix issues before reporting done.

## 5. Process rules (milestones)
- Work in **milestones**. Each milestone = implement, unit tests, build + lint green, screenshots checked, docs updated, commit, push. Never start the next milestone in the same agent run.
- A separate read-only **audit agent** reviews each milestone and returns ranked findings; the writer then fixes the valid ones (verify each claim in code before acting; findings are advice, not orders), adds a regression test per fix, re-runs everything, pushes.
- Do not get stuck: if something blocks you for more than ~20 minutes of effort, simplify the approach, document the limitation in `docs/PROGRESS.md`, and move on.
- Keep `docs/PROGRESS.md` (state, next steps, known issues, decisions) and `docs/ARCHITECTURE.md` (one line per file) current at the end of every milestone. `CLAUDE.md` is the agent entry point: commands, conventions, design rules. Future agents must not need to re-read the whole repo.
- Final report of a milestone (to the orchestrator): max 15 lines: what was built, test count, commit hash, anything unverified, anything needing a decision. No fluff.

## 6. Engineering lessons from the Truck Dash audit (apply them here)
- Every "stale" state needs a timeout path: if data stops arriving, the UI must say so (stale badge with age) instead of showing old numbers as live.
- Never derive a counter from rows the user can delete. Never trust the device wall clock at boot (it can be wrong); prefer server/provider timestamps for market data and show them.
- Background/network code must be crash-proof: catch everything at the boundary, log, degrade; never let an exception kill the process.
- Check permissions before starting anything that needs them (a location foreground service without permission crashes on Android 14+).
- Bound all growth: caches have size limits and TTLs.
- Gate fix quality / sanity-check inputs (reject impossible values, partial responses, NaN).
- One regression test per bug. Prefer pure-Kotlin logic with fake sinks over Android-dependent tests.
- Network: set connect/read timeouts, handle HTTP 429 with backoff + Retry-After, treat 200-with-bad-body as failure, never block the UI thread, cap response sizes, disable XML external entities when parsing RSS.
- Fonts/typography via a platform hook (`DashFonts.family`) because shared code cannot use `R.font`.

## 7. Milestone plan
- **M0** scaffold, design system port, tabbed shell, sandbox + screenshots, docs, CI, push.
- **M1** data layer: models, `MarketDataProvider` (Yahoo chart endpoint primary, Stooq CSV fallback), HTTP client, file cache with TTL + stale-while-revalidate, fixtures, parsing tests.
- **M2** Markets UI: indices hero, watchlist rows with sparklines, FX/commodities/crypto, instrument detail with ranges, refresh policy (market-hours aware), stale/offline states, fake provider + controls in sandbox.
- **M3** News: RSS provider + parser, headlines list/detail, dedupe, time-ago, cache, briefing text builder.
- **M4** Drive safety: GPS speed gate, glance mode, passenger mode, TTS briefing (Android) behind an interface.
- **M5** Settings and polish: watchlist editor, feed editor, provider/diagnostics screen, connectivity handling (hotspot drops), icon, install docs, release artifacts, whole-repo audit.
