# Markets Board: agent guide (READ THIS FIRST)

Android app (Kotlin + Compose) for a 14.4" **portrait** head unit in a 2015 GMC Sierra 1500 (owner is in Canada).
Glanceable markets watchlist + news over the owner's phone hotspot or the unit's LTE. Sister app of Truck Dash; a separate
app on purpose so a network failure can never touch the speedometer. Full background and the milestone plan: `docs/PLAYBOOK.md` (do not edit it).

**Do not re-read the whole repo.** Workflow for every session:
1. Read `docs/PROGRESS.md` (state, next steps, known issues, decisions).
2. Read `docs/ARCHITECTURE.md` (file map, one line per file) and open only the files you need.
3. Verify UI/logic changes in the sandbox (`docs/SANDBOX.md`) and LOOK at the screenshots.
4. After changing anything: update `docs/PROGRESS.md` (and `ARCHITECTURE.md` if files were added, moved or renamed). Commit.

## Commands
```bash
export ANDROID_HOME=/home/user/android-sdk   # SDK in the cloud sandbox; local.properties has sdk.dir
./gradlew testDebugUnitTest :sandbox:test --no-daemon -q     # logic tests (shared code + sandbox)
./gradlew assembleDebug                                      # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
./gradlew :sandbox:screenshots                               # headless PNGs of the real UI -> sandbox/build/screenshots/
./gradlew :sandbox:sandboxJar                                # Windows-ready desktop jar -> sandbox/build/libs/markets-board-sandbox.jar
```
Use `--no-daemon -q` and long timeouts in the cloud container. HTTP 429 from Maven Central: rerun once.
Live market/news hosts are NOT reachable from the container: providers are tested against recorded fixtures only.

## Architecture
- `:app` Android, `:sandbox` desktop JVM, plus plain source dir `shared/src/main/kotlin/com/marketsboard/` compiled by BOTH (not KMP).
- **No `android.*`, `androidx.lifecycle`, `androidx.activity` imports in `shared/`.** Android-only things are injected (`LocalBackHook`, `DashFonts.family`, repository interfaces).
- Put logic in pure Kotlin under `shared/.../core` (or the data layer packages) and unit test it in `app/src/test/kotlin/com/marketsboard/`. UI stays stateless and thin; `*Presenter` classes hold `StateFlow`s.
- Java APIs in shared code must exist on Android API 26+ AND the desktop JVM (`java.time`, `HttpURLConnection`, `java.io.File`, `javax.xml.parsers`). No `org.json` (use kotlinx.serialization or hand parsing).
- `DesignScale` lays out as if the screen were 440 dp wide whatever the real density. Fonts: `DashFonts.family` is set by the platform (Android `R.font.geist_*`, desktop `loadDashFonts()`).
- Version catalog `gradle/libs.versions.toml`; do not hard-code versions in build files. minSdk 26, targetSdk 34, Kotlin 2.1, AGP 8.9.1, Compose BOM 2025.01.
- Network code must be crash-proof (catch at the boundary), time-boxed, size-capped; every stale state needs a visible timeout path.

## Design rules (owner's hard preferences)
- Premium, Apple / Wealthsimple feel. Matte near-black `#0A0A0B`, surfaces `#131315` / `#1C1C1F`, hairlines `#28282C`. Hairline rows, no stacked coloured cards, no glow, no gradients on surfaces.
- Typeface Geist (Light big numerals, Regular body, Medium labels). Labels: 11sp Medium UPPERCASE, letterSpacing 1.3sp. Tabular figures for live numbers.
- **Colour is meaning.** White is the accent. `Dash.green` / `Dash.red` ONLY for price change (via `changeColor`), `Dash.amber` for stale/offline. Everything else greyscale. No other hues.
- **No em-dashes anywhere** (UI strings, docs, code comments, commit messages). Use commas, colons, the middle dot or a hyphen.
- Dark only, portrait only, big touch targets. Dense UI is hidden above 5 km/h unless Passenger mode (M4); a minimal glance view stays.
- Units are metric (km/h, km, L, kPa, degC). Each instrument is shown in its own native currency (CAD / USD), never silently converted.
- Never put a model name anywhere in the repo.

## Git
Branch `main`; push with `git -c push.negotiate=true push origin main`. Commit trailer (last lines):
`Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` then `Claude-Session: https://claude.ai/code/session_018sQ45MNG5zi6XhvhVziVLb`.
