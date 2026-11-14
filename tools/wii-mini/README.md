# wii-mini: the Mac mini's end of the Wii tile

The Wii tile in the launcher is a hook: `GET http://<mac-mini>:8766/play`. On the Mac mini under
the TV, `wii.py` makes the Wii behave like an app on the TV:

- **Opening** (`/play`): a paused Wii thaws where it was; otherwise Dolphin starts straight into
  the Wii Menu, full screen. With wii-channels installed (below) it starts through
  `wiichan.py start-dolphin`; without, in batch mode with this file's flags (`-b -C
  Dolphin.Display.Fullscreen=True -C Dolphin.Interface.CursorVisibility=0 -n 0000000100000002`).
  The display wakes, and the TV switches to the Mac's HDMI input by injecting
  `KEYCODE_TV_INPUT_HDMI_2` (244) over the TV's network adb, but only once a Dolphin window fills
  the screen, so the TV never shows the desktop or Dolphin's game list while it starts. Measured
  2026-11-14, from the request: game list at about 0.9 s, the whole screen covered 50 ms later
  (below), full screen at about 1.8 s, the answer at 2.6 s. It has a deadline: the Wii up within 15 s and the TV switched by
  22 s, or it answers `{"ok": false, "error": "..."}` and never switches the TV after giving up.
  The launcher waits 30 s, keeps "Starting Wii…" up until the answer, and shows the error
  ("Wii: the Wii didn't come up", "Couldn't reach Wii") instead of switching.
- **Never Dolphin's game list.** If a running Dolphin brought forward by the tile (or by the TV
  coming back) shows something other than the Wii within 2.5 s, which in practice is its game
  list, it is quit and started over in the Wii Menu, like resetting a real Wii: the game list is
  no use with a Wii Remote. Not while someone is using the Mac (below). How it tells: the on-screen
  windows (CGWindowListCopyWindowInfo, run in the desktop session; no window names, which would
  need Screen Recording permission) include a Dolphin window as big as the display.
- **Leaving pauses it.** While Dolphin runs, a watcher asks the TV every 2 s what it shows
  (`dumpsys power`, `window` and `tv_input`; HDMI 2 is the session `HdmiInputService/HW3`).
  After 4 s on anything else (Home, another app or input, the TV asleep), or 2 min with the TV
  not answering (switched off), Dolphin is frozen where it is (SIGSTOP, 0 % CPU), and thawed when
  the TV shows the Mac again, by the tile or by the TV's own input menu. Freezing moves no focus,
  so the game is still the window in front when it thaws. Only a Wii this listener paused is
  thawed and brought forward, so it never grabs the screen from someone using the Mac. History:
  for a while the Wii was paused by bringing Finder forward instead, with Dolphin's
  `PauseOnFocusLost` (no spinning wheel over Screen Sharing), but wii-channels runs Dolphin in
  normal mode, and a normal-mode Dolphin brought back forward showed its game list on the TV
  instead of the game (2026-11-14).
- **After twelve hours paused it quits** (SIGTERM, which Dolphin honours cleanly; SIGKILL after
  10 s if not). Unsaved game progress is lost, as with a real Wii switched off.
  If Dolphin closes while the TV shows it (Wii Menu, power off), the TV goes back to the launcher.
- **Not while someone is at the Mac.** Someone using the Mac over Screen Sharing (VNC, port 5900)
  means a session connected *and* its pointer moved in the last minute. Then the Wii is not
  paused, a frozen one thaws (a frozen app shows a spinning wheel to anyone pointing at it; it
  was force-quit twice that way), and nothing is started over. While the Wii is frozen and a
  session is connected, the watcher looks every second instead of every two, so a frozen Dolphin
  thaws about a second after the pointer moves. Once the pointer has been still for a minute,
  it is frozen again. A session merely left open on a laptop does not count: at first it did,
  and the Wii never paused.
- **The Wii Remote bar** (a Mayflash DolphinBar) gets power only while Dolphin runs unpaused, so
  the Wii Remotes switch off on every pause (press a button to reconnect) and the bar's glow
  goes dark. That needs a USB hub with per-port power switching between the Mac and the bar:
  the M2 Mac mini's own ports cannot switch power (uhubctl finds nothing there; only the M4's
  front ports can). Any hub on [uhubctl's list](https://github.com/mvp/uhubctl#compatible-usb-hubs)
  works, for example Satechi's Mac Mini Hub (M1/M2): plug it in, plug the bar into it, and the
  daemon finds the bar by name (`uhubctl -s Mayflash`). Until then the bar simply stays on.
- **With wii-channels** ([Sal-Romano/wii-channels](https://github.com/Sal-Romano/wii-channels),
  game channels in the Wii Menu, deployed to `~/wii-channels`): Dolphin runs in normal mode, not
  batch mode, because channels switch games by sending Dolphin an open-document event and batch
  mode would quit instead. Its service also starts a Dolphin at login; any Dolphin that appears
  while nobody is at the Mac is treated as a Wii for the TV and paused when the TV is elsewhere.
  Dolphin opened by someone at the Mac (Screen Sharing, a keyboard) is left alone.
- **No mouse pointer on the TV.** Every open or resume parks the pointer at the middle of the
  right edge (CGWarpMouseCursorPosition), where no Dock, menu bar or hot corner opens and the
  arrow is drawn almost off screen; left at the bottom edge it held the Dock open over the game.
  Dolphin is also started with `CursorVisibility` 0 (Never) for the pointer over the game.
  Moving the pointer is not mouse movement, so it does not send a Dock that is already up back
  down.
- **A black screen between games.** A channel switching games stops Dolphin's emulation and
  boots the new game in a new game window, which goes full screen once the game runs, so the
  Mac's own desktop shows for a moment in between. The mini is set up so that moment is black:
  - Black wallpaper: macOS's own `/System/Library/Desktop Pictures/Solid Colors/Black.png`, set
    with `NSWorkspace setDesktopImageURL` (osascript -l JavaScript, run in the desktop session).
  - The Dock and the menu bar hide themselves: `defaults write com.apple.dock autohide -bool
    true` (then `killall Dock`) and `defaults write NSGlobalDomain _HIHideMenuBar -bool true`.
    The Dock's `autohide-delay` of 999999, set earlier against the Dock over the game, was
    removed: with auto-hide on, it would have left the Dock unreachable for anyone at the Mac.
  - Reduce Motion, so going in and out of full screen is a fade, not a slide:
    `com.apple.universalaccess reduceMotion` and `com.apple.Accessibility ReduceMotionEnabled`.
  - Dolphin's game window has the whole screen as its normal size, so on its way to full screen
    it covers the desktop and the game list instead of being 640x480 in the middle. That size is
    `[renderwidget] geometry` in `~/Library/Application Support/Dolphin/Config/Qt.ini`, a
    QWidget::saveGeometry() blob, rewritten while Dolphin was closed (Dolphin rewrites Qt.ini
    when it quits; the old file is kept as `Qt.ini.before-wii-*`). Dolphin keeps that size from
    then on: it saves the window's normal size each time it goes full screen.

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
  TALLogoutSavesState -bool false`), so a reboot does not leave a Dolphin window waiting. One
  that is open anyway (opened by hand) is brought forward as it is when it shows the Wii, and
  started over when it shows its game list (above), unless someone is using the Mac.
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
