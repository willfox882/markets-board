# Install Markets Board on the head unit

Markets Board is a normal Android app (package `com.marketsboard`, Android 8.0 or newer). It is not on a store: you copy the APK to the unit and install it yourself. Everything below is metric and was written without the truck at hand, so the "what to check" list at the end is the part that matters.

## 1. Get the files

Every push builds them in GitHub Actions (workflow `build`). Open the run, then Artifacts:

- `release-<commit>`: `markets-board-0.6.3-debug.apk`, `markets-board-sandbox-0.6.3.jar` and `SHA256SUMS.txt`.
- `screenshots`: PNGs of every screen at full, half and strip size.

Or build it yourself: `./gradlew assembleDebug`, the APK is `app/build/outputs/apk/debug/app-debug.apk`.

**About signing.** There is no release keystore. Every build (your own and CI) is signed with ONE fixed debug key that is committed in the repo (`app/debug.keystore`, the standard `androiddebugkey` with password `android`; it protects nothing, it only keeps the signature the same). So from version 0.6.2 on you can install any newer APK over the old one, from any machine, and the watchlist, feeds and settings stay. One-time exception: if you installed an earlier build made by CI (0.6.1 or older), it was signed with a random key that no longer exists; Android then refuses the update with "signatures do not match", so uninstall it once first (the watchlist and feed list live inside the app and are lost, re-adding them takes a minute). A build made on your own computer before this change has your own machine's debug key and needs the same one-time uninstall.

Check the download against `SHA256SUMS.txt` if you like: `sha256sum markets-board-0.6.3-debug.apk`.

## 2. Install

### A. From a computer with USB and adb
1. On the unit open Settings, About, and tap "Build number" seven times to enable Developer options. In Developer options switch on USB debugging.
2. Connect the computer to the unit's USB port that supports data (on some units it is the one behind the glovebox or the one marked for service).
3. `adb devices` must list the unit; accept the prompt on the screen.
4. `adb install -r markets-board-0.6.3-debug.apk`  (`-r` keeps your data when updating).
5. Start it from the launcher, or `adb shell am start -n com.marketsboard/.MainActivity`.

Over Wi-Fi instead of USB (same network, after one USB connection): `adb tcpip 5555`, then `adb connect <unit address>:5555`.

### B. With the unit's file manager
1. Copy the APK to a USB stick (FAT32 or exFAT) or to the unit's storage.
2. Open the file manager on the unit and tap the APK.
3. If Android says installs from this source are blocked, allow "Install unknown apps" for the file manager when asked, then tap the APK again.
4. Tap Install, then Open.

If your unit has no package installer at all, use method A.

## 3. Internet: the phone hotspot

Markets Board needs a connection for prices and headlines. Use your phone's hotspot (or the unit's own LTE if it has a SIM).

1. iPhone: Settings, Personal Hotspot, Allow Others to Join. Keep "Maximise Compatibility" on if the unit is slow to find it. Android phone: Settings, Network, Hotspot.
2. On the unit open Wi-Fi settings and join the phone. Tick "auto connect" so it comes back by itself.
3. Data use is small: the five default news feeds plus up to six per-symbol feeds (one for each watchlist symbol) every ten minutes (every minute only while one is failing, and a failing feed waits out its own backoff), and quotes about once a minute while a market is open. A rough estimate is 10 to 20 MB an hour, not measured. All of it runs only while the app is on screen: when you leave it nothing is fetched, except that a briefing already speaking keeps going until it ends or you stop it. The GPS (only if the Driving lock is on) is read only while the app is on screen too.

When the hotspot drops, the app keeps showing the last data under one amber line, "Offline, showing last data", and stops retrying until the network is back. When it returns the app refreshes by itself within a few seconds. You do not need to press anything.

## 4. First run and permissions

- **Location**: the app does NOT ask for location at first. The driving lock is off by default, and with it off the GPS is never requested or read. When you switch on Settings, Driving, "Driving lock", the app asks for location; it is used only to read the truck's speed (so the app can switch to the minimal driving view above 5 km/h). No position is shown, stored or logged. If you say no, the app works but never locks; Settings, Driving, "Grant location permission" (shown while the lock is on) asks again (or opens the app's settings page if Android will not show the dialog any more).
- **Network**: no prompt, it is a normal permission.
- **Speech**: the spoken briefing uses the unit's text-to-speech engine. If the unit has none, "Brief me" shows the briefing as text. Install a speech engine (for example Google Speech Services) and a Canadian or US English voice if you want it spoken.
- Nothing else is requested. There is no account, no sign-in and no key.

## 5. Split screen use

The app accepts any pane size: full screen, a top or bottom half, or a short strip. Open the app in a pane with the unit's own split-screen control (the ROM decides how; usually long-press or drag from the recent-apps view). Layouts shrink by themselves: below about 620 dp of height the header gets compact, below about 330 dp it becomes a strip with one or two rows.

Two things to know: the app reads the speed whenever it is visible, so a half pane is enough to keep the driving lock working (when it is switched on); and a text field (adding a symbol or a feed) opens the keyboard: the add screens scroll and move above it, but the keyboard can take most of a short strip, so a bigger pane is easier for typing.

## 6. Settings you will want

- **Settings, News feeds**: turn sources on and off, move, rename, remove, add your own (https address only), reset to the five defaults, and the switch for "feeds for my symbols".
- **Settings, Diagnostics**: per price provider and per news feed: last success, last error in plain words, how many answers were new or "not modified" (304), whether a feed is resting after failures, the clock check, the age of every watchlist symbol, and the status of the speed source. Open it first when something looks stale.
- **Settings, Driving**: the "Driving lock" switch (off by default). Off: everything stays available at any speed and the GPS is not used. On: above 5 km/h editing and detail are hidden until the truck has been stopped for 10 s. With the lock on, Passenger mode keeps the full screen while the truck moves (it switches itself off after 10 minutes stopped). The briefing works at any speed in both cases.

## 7. What to check in the truck (first drive)

None of this could be tested without the unit; please tick it off once and tell the next session what failed.

1. **Install and start**: the icon is a thin white line chart on black. The app opens in portrait without a white flash.
2. **Hotspot**: join it, open Markets. Prices appear within about 10 seconds; the header says "Live" (or "Markets closed" outside TSX and NASDAQ hours, 9:30 AM to 4:00 PM ET).
3. **Hotspot drop**: switch the phone's hotspot off. Within seconds the amber "Offline, showing last data" line appears, rows keep their numbers (dimmed with their age), no spinner keeps turning. Switch it on: the line goes away by itself and prices update.
4. **No-internet hotspot**: join a hotspot whose phone has no data. The unit says "connected" but fetches fail. The same amber line should appear after the first failed refresh, and clear when data returns.
5. **Date and time**: if the unit boots with a wrong clock, "Check the date and time" should show, and Diagnostics, Connection, "Device clock" should say how far off it is.
6. **Speed lock**: with the default (lock off) the screen must stay fully usable at any speed and no location prompt appears. Then switch on Settings, Driving, "Driving lock" and allow location. Drive above 5 km/h: the screen should change to names, prices and the top headline, Settings dead, a big "Brief me". At a stop it should unlock after 10 seconds. A red light must not unlock it.
7. **Speech**: press "Brief me" parked. Listen for how "X E Q T", "U S" and the prices sound, the volume against the radio, and that the radio ducks and comes back. Try the media play key if you enabled it.
8. **Split screen**: put the app in the top half and in the bottom half, then in a strip. Nothing should be cut off or overlap the tab bar; when you add a feed the sheet must scroll and the focused field and the Add button must stay reachable above the keyboard (even in a short strip, where you may have to scroll).
9. **Feeds**: open Settings, News feeds, add a real https feed, watch it in News after a minute. Then Diagnostics shows it as "Working".
10. **Rotation and sleep**: rotate or sleep the unit and wake it. The app should come back with the last data and refresh within a minute.

## 8. Update and uninstall

Install the next APK with `adb install -r` (or the file manager). Your watchlist, feeds and settings stay. To remove: long-press the icon, Uninstall, or `adb uninstall com.marketsboard` (this deletes the watchlist and feeds).

## 9. If something does not work

- Open Settings, Diagnostics first. "No network" means the unit has no connection; "Connected" with failing feeds means the hotspot has no internet.
- Prices missing but headlines fine (or the other way round) means one provider is resting: the Prices section shows "Resting 5 min after 3 failures".
- A stuck screen: close the app from recent apps and open it again; nothing is lost.
- Live market and news hosts could not be reached from the build container, so the providers have only been tested against recorded samples. The first drive is the first live test.
