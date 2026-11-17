# Markets Board

A glanceable markets watchlist and news board for a 14.4" portrait head unit (2015 GMC Sierra), in the same dark,
hairline-and-Geist visual language as the sister app Truck Dash. It is a separate app so a network failure can never
affect the speedometer.

- Default watchlist: XEQT.TO (C$, CAD) and MSFT (US$, USD), each in its own native currency; fully editable later.
- Tabs: Markets, News, Settings. Metric units. Dark only, portrait only.
- Kotlin + Jetpack Compose; UI code in `shared/` runs on Android and on a desktop sandbox.

Status: milestone M5 (version 0.6.0). Install: `docs/INSTALL.md`. State: `docs/PROGRESS.md`.

```bash
./gradlew testDebugUnitTest :sandbox:test assembleDebug lintDebug :sandbox:sandboxJar :sandbox:screenshots
```
Agents: start with `CLAUDE.md`. Desktop simulator: `docs/SANDBOX.md`.
