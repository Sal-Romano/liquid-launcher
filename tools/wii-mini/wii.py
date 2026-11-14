#!/usr/bin/env python3
"""
wii: the Mac mini's end of the launcher's Wii tile. Runs as a root launch daemon (README.md).

The Wii behaves like an app on the TV:

- /play opens it. A paused Wii thaws; otherwise Dolphin boots straight into the Wii Menu,
  fullscreen, with no Dolphin window in the way. The display wakes and the TV switches to this
  Mac's input while the Wii Menu loads.
- Leaving pauses it. While Dolphin runs, a watcher asks the TV every 2 s what it is showing.
  When it has shown something else for a few seconds (Home, another app or input, the TV
  asleep), Dolphin is frozen exactly where it was. When the TV shows this Mac again, however it
  got there, Dolphin thaws.
- While someone is using this Mac over Screen Sharing (a session connected and its pointer
  moved in the last minute), Dolphin is never left frozen under them: it does not pause, and a
  paused one thaws. A session merely left open on a laptop does not count.
- After twelve hours paused, Dolphin quits. Anything not saved in the game is lost, as when a
  real Wii is switched off.
- /play has a deadline: the Wii up within 15 s and the TV switched by 22 s, or it answers with
  a short error for the TV and never switches the TV after giving up. The launcher waits 30 s.
- The Wii Remote bar has power only while Dolphin runs unpaused, so the Wii Remotes switch off
  on every pause (press a button to reconnect) and the bar's glow goes dark. That needs a USB
  hub with per-port power switching between the Mac and the bar; the M2 Mac mini's own ports
  cannot do it (uhubctl finds nothing there). Without such a hub the bar simply stays on.

/home sends the TV to its launcher, /quit quits Dolphin and goes home, /status reports.
LAN only, no authentication: it trusts the house.
"""
import json
import os
import re
import signal
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CFG = {
    "tv": "192.168.1.152:5555",
    "hdmi_key": 244,                       # KEYCODE_TV_INPUT_HDMI_2
    "hdmi_input": "HdmiInputService/HW3",  # HDMI 2, as the TV's dumpsys tv_input names it
    "port": 8766,
    "app": "Dolphin",
    # No Dolphin UI, fullscreen whatever the GUI says, no mouse pointer over the game
    # (CursorVisibility 0 = Never), straight into the Wii System Menu.
    "boot": ["-b", "-C", "Dolphin.Display.Fullscreen=True", "-C", "Dolphin.Interface.CursorVisibility=0",
             "-n", "0000000100000002"],
    "poll_s": 2.0,
    "away_s": 4.0,             # the TV shows something else this long: the Wii pauses
    "lost_s": 120.0,           # the TV does not answer this long (switched off): the Wii pauses
    "quit_after_s": 12 * 3600,  # paused this long: Dolphin quits
    "up_within_s": 15.0,        # /play: the Wii must be up by then...
    "switch_by_s": 22.0,        # ...and the TV switched by then, or /play gives up (launcher waits 30)
    "in_use_s": 60.0,           # Screen Sharing counts as someone at the Mac if the pointer moved this recently
    "bar": "Mayflash",         # the Wii Remote bar, as uhubctl describes it
}
HOME = os.path.expanduser("~")
DIR = os.path.join(HOME, "Library/Application Support/wii")
ADB = os.path.join(HOME, "Library/Android/platform-tools/adb")
UHUBCTL = os.path.join(DIR, "uhubctl")
STATE = os.path.join(DIR, "state.json")
USER = os.environ.get("WII_USER", "sal")
UID = os.environ.get("WII_UID", "502")
LOCK = threading.RLock()


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def sh(*args, timeout=20):
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(args, 124, "", "timeout")


def as_user(*args):
    """Run in the console user's GUI session; this is a system daemon."""
    return sh("launchctl", "asuser", UID, "sudo", "-u", USER, *args)


def state():
    try:
        with open(STATE) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save(s):
    tmp = STATE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(s, f)
    os.replace(tmp, STATE)


# ---- the TV, over its network adb (with a key the TV already trusts)

def adb(*args, timeout=10, until=None):
    """One adb command to the TV. Its Wi-Fi adb goes 'offline' now and then and only a
    disconnect/connect clears it, so a failure gets one reconnect and one retry. With [until],
    no step runs past that time."""
    def t(cap):
        return cap if until is None else max(0.5, min(cap, until - time.time()))
    r = sh(ADB, "-s", CFG["tv"], *args, timeout=t(timeout))
    if r.returncode != 0 and (until is None or time.time() < until):
        sh(ADB, "disconnect", CFG["tv"], timeout=t(3))
        sh(ADB, "connect", CFG["tv"], timeout=t(3))
        r = sh(ADB, "-s", CFG["tv"], *args, timeout=t(timeout))
    return r


def tv_key(code, until=None):
    for _ in range(3):
        if adb("shell", "input", "keyevent", str(code), timeout=4, until=until).returncode == 0:
            return True
        if until is not None and time.time() + 1.0 >= until:
            break
        time.sleep(1.0)
    log("tv key", code, "failed")
    return False


def tv_view():
    """'mac' when the TV shows this Mac's input, 'away' when it shows anything else or is
    asleep, None when it did not answer."""
    r = adb("shell", "dumpsys power | grep mWakefulness=; dumpsys window | grep mCurrentFocus=;"
            " dumpsys tv_input | grep inputId:; true")
    out = r.stdout
    if r.returncode != 0 or "mWakefulness=" not in out:
        return None
    showing = ("mWakefulness=Awake" in out and "PassthroughPlayerActivity" in out
               and CFG["hdmi_input"] in out)
    return "mac" if showing else "away"


# ---- the Wii Remote bar, through a hub with per-port power switching

_no_hub_until = 0.0


def bar_where():
    """(hub location, port) of the bar as uhubctl sees it. Kept in the state file, because once
    its port is off the bar no longer shows up by name."""
    if os.path.exists(UHUBCTL):
        hub = None
        for line in sh(UHUBCTL, "-s", CFG["bar"], timeout=15).stdout.splitlines():
            m = re.match(r"Current status for hub (\S+)", line)
            if m:
                hub = m.group(1)
                continue
            m = re.match(r"\s*Port (\d+):", line)
            if hub and m and CFG["bar"].lower() in line.lower():
                return [hub, m.group(1)]
    return state().get("bar_where")


def bar(on):
    """Power the bar on or off. Without a switchable hub there is nothing to switch; that is
    checked again every five minutes, in case a hub has been plugged in since."""
    global _no_hub_until
    s = state()
    if s.get("bar_on") == on and s.get("bar_where"):
        return True
    if time.time() < _no_hub_until:
        return False
    where = bar_where()
    ok = False
    if where:
        r = sh(UHUBCTL, "-l", where[0], "-p", where[1], "-a", "on" if on else "off", timeout=15)
        ok = r.returncode == 0
        log("bar", "on" if on else "off", "ok" if ok else (r.stdout + r.stderr).strip()[-200:])
    if not ok:
        _no_hub_until = time.time() + 300
        return False
    s = state()
    s["bar_on"], s["bar_where"] = on, where
    save(s)
    return True


# ---- someone at the Mac itself

_shared = {"at": 0.0, "value": False}


def screen_shared(max_age=0.0):
    """Someone is using this Mac over Screen Sharing (or any VNC client): an established TCP
    connection on port 5900, or one held by screensharingd. Cached for [max_age] seconds."""
    if time.time() - _shared["at"] > max_age:
        r = sh("lsof", "-nP", "-iTCP", "-sTCP:ESTABLISHED", timeout=10)
        _shared["value"] = any(
            line.startswith("screensha") or ":5900->" in line for line in r.stdout.splitlines()[1:])
        _shared["at"] = time.time()
    return _shared["value"]


# ---- Dolphin

def dolphin():
    r = sh("pgrep", "-x", CFG["app"])
    return int(r.stdout.split()[0]) if r.returncode == 0 and r.stdout.strip() else None


def ps(pid, field):
    return sh("ps", "-o", field + "=", "-p", str(pid)).stdout.strip()


def frozen(pid):
    return "T" in ps(pid, "stat")


def batch(pid):
    args = " " + ps(pid, "args") + " "
    return " -b " in args or " --batch " in args


def busy(pid):
    """A Dolphin that is emulating something uses real CPU; one showing its game list does not."""
    try:
        return float(ps(pid, "%cpu") or 0) >= 8.0
    except ValueError:
        return True


def wake():
    """Light the Mac's display if it had gone dark."""
    subprocess.Popen(["launchctl", "asuser", UID, "sudo", "-u", USER, "caffeinate", "-u", "-t", "8"])


PARK = '''
import ctypes, ctypes.util
class P(ctypes.Structure): _fields_ = [("x", ctypes.c_double), ("y", ctypes.c_double)]
class R(ctypes.Structure): _fields_ = [("x", ctypes.c_double), ("y", ctypes.c_double), ("w", ctypes.c_double), ("h", ctypes.c_double)]
cg = ctypes.cdll.LoadLibrary(ctypes.util.find_library("CoreGraphics"))
cg.CGMainDisplayID.restype = ctypes.c_uint32
cg.CGDisplayBounds.restype, cg.CGDisplayBounds.argtypes = R, [ctypes.c_uint32]
cg.CGWarpMouseCursorPosition.argtypes = [P]
b = cg.CGDisplayBounds(cg.CGMainDisplayID())
cg.CGWarpMouseCursorPosition(P(b.x + b.w - 1, b.y + b.h / 2))
print(int(b.x + b.w - 1), int(b.y + b.h / 2))
'''

POINTER = '''
import ctypes, ctypes.util
class P(ctypes.Structure): _fields_ = [("x", ctypes.c_double), ("y", ctypes.c_double)]
cg = ctypes.cdll.LoadLibrary(ctypes.util.find_library("CoreGraphics"))
cg.CGEventCreate.restype, cg.CGEventCreate.argtypes = ctypes.c_void_p, [ctypes.c_void_p]
cg.CGEventGetLocation.restype, cg.CGEventGetLocation.argtypes = P, [ctypes.c_void_p]
p = cg.CGEventGetLocation(cg.CGEventCreate(None))
print(int(p.x), int(p.y))
'''

_pointer = {"pos": None, "moved_at": 0.0}


def _pointer_seen(out):
    """Record where the pointer is, from a script's 'x y' output; returns the position."""
    parts = out.split()
    return (parts[0], parts[1]) if len(parts) >= 2 else None


def pointer_moved(within):
    """The pointer has moved in the last [within] seconds: someone is driving this Mac. The first
    reading, and the listener's own parking, do not count as movement."""
    pos = _pointer_seen(as_user("/usr/bin/python3", "-c", POINTER).stdout)
    if pos is not None:
        if _pointer["pos"] is not None and pos != _pointer["pos"]:
            _pointer["moved_at"] = time.time()
        _pointer["pos"] = pos
    return time.time() - _pointer["moved_at"] < within


def mac_in_use():
    """Someone is using this Mac over Screen Sharing right now: a session is connected and its
    pointer moved in the last minute. A session merely left open on a laptop does not count; it
    did, at first, and the Wii never paused while Sal had Screen Sharing open (2026-11-14)."""
    return screen_shared(max_age=15) and pointer_moved(CFG["in_use_s"])


def park_cursor():
    """Nobody uses a mouse with the Wii: put the pointer at the middle of the right edge, where no
    Dock, menu bar or hot corner opens and the arrow is drawn almost entirely off screen. Left at
    the bottom edge it held the Dock open over the game (2026-11-14). Moving it this way is not
    mouse movement, so a Dock already up stays up; the Dock's own delay (README) is what keeps it
    down, and parking before the game shows keeps the pointer off the edges to begin with."""
    pos = _pointer_seen(as_user("/usr/bin/python3", "-c", PARK).stdout)
    if pos is not None:
        _pointer["pos"] = pos  # our own move, not someone at the Mac


def console_user():
    r = sh("stat", "-f", "%Su", "/dev/console")
    return r.stdout.strip() if r.returncode == 0 else ""


def pause(pid):
    """The Wii Remotes off with the bar, then Dolphin frozen exactly where it was."""
    bar(False)
    time.sleep(0.5)  # let Dolphin notice the remotes going before it stops
    os.kill(pid, signal.SIGSTOP)
    s = state()
    s["paused_at"] = time.time()
    save(s)
    log("paused", pid)


def resume(pid):
    os.kill(pid, signal.SIGCONT)
    bar(True)
    s = state()
    s.pop("paused_at", None)
    save(s)
    log("resumed", pid)


def end_session():
    s = state()
    for k in ("session", "paused_at", "last_mac"):
        s.pop(k, None)
    save(s)
    bar(False)


def stop(pid):
    """Quit Dolphin: thaw it, ask it to close, and kill it only if it has not gone in 10 s."""
    try:
        os.kill(pid, signal.SIGCONT)
        time.sleep(1.0)
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    for _ in range(40):
        if dolphin() is None:
            break
        time.sleep(0.25)
    else:
        try:
            os.kill(pid, signal.SIGKILL)
            log("Dolphin did not close on SIGTERM; killed")
        except ProcessLookupError:
            pass
    end_session()
    log("quit", pid)


# ---- what the Wii tile and the watcher do

def play():
    t0 = time.time()
    up_by, switch_by = t0 + CFG["up_within_s"], t0 + CFG["switch_by_s"]
    if not LOCK.acquire(timeout=CFG["up_within_s"]):
        return {"ok": False, "error": "the Mac is busy, try again"}
    try:
        return _play(t0, up_by, switch_by)
    finally:
        LOCK.release()


def _play(t0, up_by, switch_by):
    # Right after a boot the daemon is up before the auto-login has finished.
    while console_user() in ("", "root"):
        if time.time() >= up_by:
            return {"ok": False, "error": "the Mac is still starting up"}
        time.sleep(0.5)
    wake()
    park_cursor()  # before the game is on screen, so the pointer is never at an edge in full screen
    pid = dolphin()
    if pid and not batch(pid) and not frozen(pid) and not busy(pid):
        # A Dolphin window left open (macOS reopens apps at login), not a Wii: replace it.
        stop(pid)
        pid = None
    started = pid is None
    if started:
        as_user("open", "-a", CFG["app"], "--args", *CFG["boot"])
        while dolphin() is None and time.time() < up_by:
            time.sleep(0.2)
        pid = dolphin()
        if pid is None:
            log("play: Dolphin did not start in time")
            return {"ok": False, "error": "Dolphin didn't start"}
    elif frozen(pid):
        resume(pid)
    else:
        as_user("open", "-a", CFG["app"])  # to the front
    park_cursor()
    bar(True)
    s = state()
    s["session"], s["last_mac"] = True, time.time()
    s.pop("paused_at", None)
    save(s)
    if started:
        time.sleep(max(0.0, min(2.5, up_by - time.time())))  # switch while the Wii Menu loads
        if dolphin() is None:
            end_session()
            log("play: Dolphin closed as it started")
            return {"ok": False, "error": "Dolphin closed as it started"}
    if time.time() >= switch_by:  # the launcher may have given up: never switch after that
        log("play: too slow, not switching the TV")
        return {"ok": False, "error": "it took too long, try again"}
    if not tv_key(CFG["hdmi_key"], until=switch_by):
        return {"ok": False, "error": "couldn't switch the TV"}
    return {"ok": True, "started": started, "switched": True,
            "took_ms": int((time.time() - t0) * 1000)}


def tick():
    s = state()
    pid = dolphin()
    if pid is None:
        if s.get("session"):
            log("Dolphin closed")
            on_mac = tv_view() == "mac"
            end_session()
            if on_mac:
                tv_key(3)  # like an app closing: back to the launcher
        else:
            bar(False)  # nothing to play: the remotes off, the mantle dark
        return
    view = tv_view()
    now = time.time()
    if view == "mac":
        s["session"], s["last_mac"] = True, now  # also when the TV's own input menu got here
        save(s)
        if frozen(pid):
            wake()
            park_cursor()
            resume(pid)
        return
    if not s.get("session"):
        bar(True)  # Dolphin opened on the Mac itself (Screen Sharing, a keyboard): leave it be
        return
    if frozen(pid):
        if mac_in_use():
            log("thawing: someone is using the Mac over Screen Sharing")
            resume(pid)
        elif now - s.get("paused_at", now) >= CFG["quit_after_s"]:
            stop(pid)
        return
    away = now - s.get("last_mac", now)
    if (view == "away" and away >= CFG["away_s"]) or (view is None and away >= CFG["lost_s"]):
        if mac_in_use():
            return  # someone is using the Mac over Screen Sharing: do not freeze Dolphin under them
        pause(pid)


def watch():
    while True:
        time.sleep(CFG["poll_s"])
        try:
            with LOCK:
                tick()
        except Exception as e:  # the watcher must outlive anything one tick does
            log("watch:", repr(e))


def home():
    return {"ok": tv_key(3)}


def quit_():
    with LOCK:
        pid = dolphin()
        if pid:
            stop(pid)
        return {"ok": True, "stopped": dolphin() is None, "home": tv_key(3)}


def status():
    s = state()
    pid = dolphin()
    return {"ok": True, "running": pid is not None, "paused": bool(pid and frozen(pid)),
            "session": bool(s.get("session")), "tv": tv_view(), "bar_on": s.get("bar_on"),
            "bar_where": s.get("bar_where")}


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?")[0]
        try:
            handler = {"/play": play, "/home": home, "/quit": quit_, "/status": status}.get(path)
            if handler is None:
                self.send_response(404)
                self.end_headers()
                return
            out = json.dumps(handler()).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
        except Exception as e:  # never die on a bad request
            out = json.dumps({"ok": False, "error": str(e)}).encode()
            self.send_response(500)
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, fmt, *args):
        log(self.address_string(), fmt % args)


if __name__ == "__main__":
    log("wii listening on", CFG["port"])
    threading.Thread(target=watch, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", CFG["port"]), H).serve_forever()
