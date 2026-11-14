# wii-mini: the Mac mini's end of the Wii tile

The Wii tile in the launcher is a hook: `GET http://<mac-mini>:8766/play`. On the Mac mini under
the TV, `wii.py` answers it: opens Dolphin, waits for it, then switches the TV to the Mac's HDMI
input by injecting `KEYCODE_TV_INPUT_HDMI_2` (244) over the TV's network adb. `/home` sends the TV
back to the launcher, `/quit` stops Dolphin and goes home, `/status` says what is running.

Setup, as done on the Hisense's Mac mini (VOXCON, 192.168.1.58) on 2026-11-13:

- Dolphin 2609b in /Applications (from dl.dolphin-emu.org, checksum from Homebrew's cask metadata).
  Config written before first run: fullscreen, no stop confirmation, continuous Wii Remote
  scanning, analytics declined, Wiimote 1 = real remote over the Mac's Bluetooth.
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
