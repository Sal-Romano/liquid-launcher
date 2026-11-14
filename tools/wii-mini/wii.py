#!/usr/bin/env python3
"""
wii: the Mac mini's end of the launcher's Wii tile.

A tiny HTTP service on the home network. GET /play opens Dolphin, waits until it is running, then
switches the TV to this Mac's HDMI input over adb (the TV's debug port, with a key the TV already
trusts). GET /home sends the TV back to its launcher. GET /quit stops Dolphin and goes home.
GET /status says what is running. LAN only, no authentication: it trusts the house.
"""
import json, os, subprocess, time
from http.server import BaseHTTPRequestHandler, HTTPServer

CFG = {"tv": "192.168.1.152:5555", "hdmi_key": 244, "port": 8766, "app": "Dolphin"}  # 244 = KEYCODE_TV_INPUT_HDMI_2
ADB = os.path.expanduser("~/Library/Android/platform-tools/adb")
USER = os.environ.get("WII_USER", "sal"); UID = os.environ.get("WII_UID", "502")
def as_user(*args):  # run in the console user's GUI session; we are a system daemon
    return sh("launchctl", "asuser", UID, "sudo", "-u", USER, *args)

def sh(*args, timeout=20):
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout)

LAST_ADB = {"out": ""}
def tv_key(code):
    """A fresh adb connection to the TV each time: its Wi-Fi adb goes offline now and then and only a
    disconnect/connect clears it. Up to three tries, a second apart."""
    log = []
    for attempt in range(3):
        sh(ADB, "disconnect", CFG["tv"])
        c = sh(ADB, "connect", CFG["tv"]); log.append(c.stdout.strip() or c.stderr.strip())
        if "connected" in c.stdout:
            r = sh(ADB, "-s", CFG["tv"], "shell", "input", "keyevent", str(code)); log.append((r.stdout + r.stderr).strip())
            if r.returncode == 0:
                LAST_ADB["out"] = " | ".join(x for x in log if x); print("adb ok:", LAST_ADB["out"], flush=True); return True
        time.sleep(1.0)
    LAST_ADB["out"] = " | ".join(x for x in log if x); print("adb failed:", LAST_ADB["out"], flush=True)
    return False

def running():
    return sh("pgrep", "-x", CFG["app"]).returncode == 0

def wake():
    """Nudge the Mac awake: a user-activity assertion lights the display if it had gone dark."""
    subprocess.Popen(["launchctl", "asuser", UID, "sudo", "-u", USER, "caffeinate", "-u", "-t", "8"])

def console_user():
    r = sh("stat", "-f", "%Su", "/dev/console")
    return r.stdout.strip() if r.returncode == 0 else ""

def play():
    t0 = time.time()
    # Right after a boot the daemon is up before the auto-login finishes: give the desktop a moment.
    while console_user() in ("", "root") and time.time() - t0 < 40:
        time.sleep(0.5)
    wake()
    was = running()
    as_user("open", "-a", CFG["app"])
    while not running() and time.time() - t0 < 15:
        time.sleep(0.2)
    if not running():
        return {"ok": False, "error": f"{CFG['app']} did not start"}
    if not was:
        time.sleep(2.5)  # let its window come up before the TV looks at it
    switched = tv_key(CFG["hdmi_key"])
    return {"ok": True, "started": not was, "switched": switched, "took_ms": int((time.time() - t0) * 1000)}

def home():
    ok = tv_key(3)
    return {"ok": ok, "adb": LAST_ADB["out"]}

def quit_():
    sh("pkill", "-u", USER, "-x", CFG["app"])
    time.sleep(0.5)
    return {"ok": True, "stopped": not running(), "home": tv_key(3)}

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?")[0]
        try:
            body = {"/play": play, "/home": home, "/quit": quit_, "/status": lambda: {"ok": True, "running": running()}}.get(path)
            if body is None:
                self.send_response(404); self.end_headers(); return
            out = json.dumps(body()).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)
        except Exception as e:  # never die on a bad request
            out = json.dumps({"ok": False, "error": str(e)}).encode()
            self.send_response(500); self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)
    def log_message(self, fmt, *args):
        print(time.strftime("%H:%M:%S"), self.address_string(), fmt % args, flush=True)

if __name__ == "__main__":
    print("wii listening on", CFG["port"], flush=True)
    HTTPServer(("0.0.0.0", CFG["port"]), H).serve_forever()
