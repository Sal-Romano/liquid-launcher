# wii-mini: the Mac mini's end of the Wii tile

The Wii tile in the launcher is a hook: `GET http://<mac-mini>:8766/play`. On the Mac mini under
the TV, `wii.py` makes the Wii behave like an app on the TV:

- **Opening** (`/play`): a paused Wii thaws; otherwise Dolphin boots straight into the Wii Menu,
  fullscreen, with no Dolphin window (`-b -C Dolphin.Display.Fullscreen=True -n
  0000000100000002`). The display wakes, and the TV switches to the Mac's HDMI input by
  injecting `KEYCODE_TV_INPUT_HDMI_2` (244) over the TV's network adb, while the Wii Menu loads.
  About 4 s from a cold start, 1-2 s to resume. It has a deadline: the Wii up within 15 s and
  the TV switched by 22 s, or it answers `{"ok": false, "error": "..."}` and never switches the
  TV after giving up. The launcher waits 30 s, keeps "Starting Wii…" up until the answer, and
  shows the error ("Wii: Dolphin didn't start", "Couldn't reach Wii") instead of switching.
- **Leaving pauses it.** While Dolphin runs, a watcher asks the TV every 2 s what it shows
  (`dumpsys power`, `window` and `tv_input`; HDMI 2 is the session `HdmiInputService/HW3`).
  After 4 s on anything else (Home, another app or input, the TV asleep), or 2 min with the TV
  not answering (switched off), Dolphin is frozen with SIGSTOP. When the TV shows the Mac again,
  by the tile or by the TV's own input menu, it gets SIGCONT. Measured: paused 3-5 s after Home,
  resumed 5 s after switching back by the input menu.
- **Not while someone is at the Mac.** While someone is using the Mac over Screen Sharing (VNC,
  port 5900): a session connected *and* its pointer moved in the last minute, Dolphin is never
  left frozen under them: it does not pause, and a paused one thaws. (2026-11-14: a paused
  Dolphin looked hung over Screen Sharing and was force-quit twice.) A session merely left open
  on a laptop does not count: at first it did, and the Wii never paused. Note that an open
  session steers the Mac's pointer from the laptop; parked at the bottom edge of the Screen
  Sharing window, it holds the Dock open over the game.
- **After twelve hours paused it quits** (SIGCONT, then SIGTERM, which Dolphin honours cleanly;
  SIGKILL after 10 s if not). Unsaved game progress is lost, as with a real Wii switched off.
  If Dolphin closes while the TV shows it (Wii Menu, power off), the TV goes back to the launcher.
- **The Wii Remote bar** (a Mayflash DolphinBar) gets power only while Dolphin runs unpaused, so
  the Wii Remotes switch off on every pause (press a button to reconnect) and the bar's glow
  goes dark. That needs a USB hub with per-port power switching between the Mac and the bar:
  the M2 Mac mini's own ports cannot switch power (uhubctl finds nothing there; only the M4's
  front ports can). Any hub on [uhubctl's list](https://github.com/mvp/uhubctl#compatible-usb-hubs)
  works, for example Satechi's Mac Mini Hub (M1/M2): plug it in, plug the bar into it, and the
  daemon finds the bar by name (`uhubctl -s Mayflash`). Until then the bar simply stays on.
- Dolphin opened on the Mac itself, not through the tile or the TV, is left alone.
- **No mouse pointer on the TV.** Every open or resume parks the pointer at the middle of the
  right edge (CGWarpMouseCursorPosition), where no Dock, menu bar or hot corner opens and the
  arrow is drawn almost off screen; left at the bottom edge it held the Dock open over the game.
  Dolphin is also started with `CursorVisibility` 0 (Never) for the pointer over the game.
  Moving the pointer is not mouse movement, so it does not send a Dock that is already up back
  down. What keeps the Dock off the game is `defaults write com.apple.dock autohide-delay
  -float 999999` (then `killall Dock`): the Dock waits that long before sliding up over a
  full-screen app. The desktop Dock does not auto-hide, so it is unaffected.

`/home` sends the TV back to the launcher, `/quit` quits Dolphin and goes home, `/status` reports
(running, paused, session, what the TV shows, the bar). State lives in `state.json` next to
`wii.py`, so a pause and its two-hour clock survive a restart of the daemon.

Setup, as done on the Hisense's Mac mini (VOXCON, 192.168.1.58) on 2026-11-13:

- Dolphin 2609b in /Applications (from dl.dolphin-emu.org, checksum from Homebrew's cask metadata).
  Config written before first run: fullscreen, no stop confirmation, continuous Wii Remote
  scanning, analytics declined, Wiimote 1 = real remote. The Wii System Menu was installed from
  Dolphin's online system update (Tools menu), 2026-11-14.
- uhubctl (master) built against a static libusb 1.0.30 (macOS 26 needs 1.0.30 or later) into
  ~/Library/Application Support/wii/uhubctl. To rebuild: libusb's release tarball with
  `./configure --disable-shared --enable-static`, then `cc uhubctl.c libusb-1.0.a -framework
  IOKit -framework CoreFoundation -framework Security -lobjc`, in a path without spaces.
- macOS does not reopen apps at login (`defaults -currentHost write com.apple.loginwindow
  TALLogoutSavesState -bool false`), so a reboot does not leave a Dolphin window waiting; one that
  is there anyway and idle gets replaced by the Wii Menu on the next `/play`.
- adb (platform-tools) in ~/Library/Android, with the TV-authorised key copied from the laptop
  into ~/.android so the TV never asks.
- Appliance power settings: `pmset -a sleep 0 disksleep 0 displaysleep 0 womp 1 autorestart 1 powernap 0`,
  screensaver off, so it is always there and its display never goes dark. `/play` also nudges it
  awake first (`caffeinate -u`) in case it did.
- Auto-login as sal, unlocked, with no keyboard ever attached (FileVault off):
  - `autoLoginUser = sal` in /Library/Preferences/com.apple.loginwindow, plus `/etc/kcpassword`
    (root, 600): the account password XOR-obfuscated, which is how macOS auto-login works and is
    reversible by anyone with root. `sudo sysadminctl -autologin set` fails over SSH with
    `SACSetAutoLoginPassword error:22`, so the file was written directly.
  - `autoLoginUserScreenLocked` must be **absent** from that plist. At 1 (it was, 2026-11-14),
    macOS auto-logs in and then locks the screen, and the lock screen turns the display off after
    about 30 s, so the TV shows a sign-in screen or "no signal".
  - Screen lock off: `sysadminctl -screenLock off -password …`, run as sal.
  - Check: after a reboot, `ioreg -n Root -d1 -a | grep -A1 CGSSessionScreenIsLocked` shows nothing.
- `wii.py` in ~/Library/Application Support/wii, run by the **system** launch daemon
  `/Library/LaunchDaemons/dev.sal.wii.plist` (HOME and WII_UID set), log in ~/Library/Logs/wii.log.

Why a system daemon and not a user agent: macOS's Local Network privacy gate blocks a user
agent's processes from the LAN ("No route to host") until someone clicks Allow on the Mac, and the
adb *server* it spawns inherits that. A system daemon is outside the gate; its adb server too.
If `/play` ever says `"switched": false` with "No route to host", an old adb server spawned by
something else is still running: `pkill -f platform-tools/adb` and let the daemon respawn it.

The TV side needs nothing beyond network adb being on, which the launcher's development already
relies on. Fire OS does not let apps switch inputs directly (the input player wants
`com.amazon.tv.permission.TUNE_INPUT`), but it honours the HDMI key codes from adb.
