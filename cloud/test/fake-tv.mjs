// A stand-in TV for working on the phone page without the launcher.
//
//   node test/fake-tv.mjs [base] [--out dir] [--id x --secret y --key z] [--name "Living room"]
//                         [--away] [--fail] [--fail-keep] [--slow ms]
//
// Connects to the relay as a TV, prints the page address to open (the same one the TV's QR code
// would hold), and for every photo offered: downloads it into --out, says "got", and ~1.5 s later
// "shown" (or "failed" with --fail; shown with visible: false with --away). A test is held as the
// preview; a keep is saved, replacing the earlier keep of the same draft; "keep" for a test saves
// the held preview and says "kept" (or "failed" with --fail-keep, to exercise the phone's
// fallback). Pings every 25 s and reconnects if dropped. Pass the printed --id/--secret/--key
// back in to be the same TV again.

import { randomBytes } from "node:crypto";
import { mkdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import { join } from "node:path";

const args = process.argv.slice(2);
const flag = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const has = (name) => args.includes(name);
const base = (args[0] && !args[0].startsWith("--") ? args[0] : "http://localhost:8787").replace(/\/$/, "");
const wsBase = base.replace(/^http/, "ws");
const out = flag("--out") ?? join(os.tmpdir(), "liquid-fake-tv");
mkdirSync(out, { recursive: true });

const b32 = (n) => [...randomBytes(n)].map((b) => "abcdefghijklmnopqrstuvwxyz234567"[b % 32]).join("");
const b64 = (n) => randomBytes(n).toString("base64url");
const id = flag("--id") ?? b32(16);
const secret = flag("--secret") ?? b64(32);
const key = flag("--key") ?? b64(16);
const name = flag("--name") ?? "Living room";
const slow = Number(flag("--slow") ?? 0);

const log = (...a) => console.log(new Date().toISOString().slice(11, 23), ...a);

console.log(`phone page: ${base}/#${id}.${key}`);
console.log(`same TV again: --id ${id} --secret ${secret} --key ${key}`);
console.log(`photos saved in: ${out}`);

let ws;
let ping;
/** The test on screen, and the saved file of each draft. */
let preview = null; // { id, draft, file }
const saved = new Map(); // draft → file

function connect() {
  ws = new WebSocket(`${wsBase}/api/tv/${id}/socket`, {
    headers: { Authorization: `Bearer ${secret}`, "X-Send-Key": key, "X-TV-Name": encodeURIComponent(name) },
  });
  ws.addEventListener("open", () => {
    log("connected");
    clearInterval(ping);
    ping = setInterval(() => ws.readyState === WebSocket.OPEN && ws.send("ping"), 25_000);
  });
  ws.addEventListener("message", (e) => {
    if (e.data === "pong") return;
    const m = JSON.parse(e.data);
    if (m.t === "wallpaper") collect(m).catch((err) => log("failed", m.id, err.message));
    else if (m.t === "keep") keep(m);
    else log("message", m);
  });
  ws.addEventListener("close", (e) => {
    clearInterval(ping);
    log(`closed ${e.code} ${e.reason}`);
    if (e.code !== 4000) setTimeout(connect, 2000);
  });
  ws.addEventListener("error", () => {});
}

async function collect(m) {
  const mode = m.mode ?? "keep";
  log(`offered ${m.id}: ${mode}, ${m.bytes} bytes from ${m.from ?? "?"}, draft ${m.draft ?? "-"}, show ${m.show}`);
  if (slow) await new Promise((r) => setTimeout(r, slow));
  const res = await fetch(`${base}/api/tv/${id}/wallpapers/${m.id}`, { headers: { Authorization: `Bearer ${secret}` } });
  if (!res.ok) throw new Error(`download ${res.status}`);
  const bytes = Buffer.from(await res.arrayBuffer());
  const ext = m.type === "image/png" ? "png" : m.type === "image/webp" ? "webp" : "jpg";
  const file = join(out, `${mode}-${m.id}.${ext}`);
  writeFileSync(file, bytes);
  log(`saved ${file}`);
  if (has("--fail")) {
    ws.send(JSON.stringify({ t: "failed", id: m.id, error: "fake TV was told to fail" }));
    return;
  }
  if (mode === "test") {
    if (preview) rmSync(preview.file, { force: true });
    preview = { id: m.id, draft: m.draft, file };
  } else if (m.draft) {
    const earlier = saved.get(m.draft);
    if (earlier) { rmSync(earlier, { force: true }); log(`replaced the earlier keep of draft ${m.draft}`); }
    saved.set(m.draft, file);
  }
  ws.send(JSON.stringify({ t: "got", id: m.id }));
  if (mode === "keep" && !m.show) return;
  setTimeout(() => {
    ws.send(JSON.stringify({ t: "shown", id: m.id, visible: !has("--away") }));
    log(`shown ${m.id}`);
  }, 1500);
}

/** Use this, for the test on screen: save it as a wallpaper. */
function keep(m) {
  if (has("--fail-keep") || !preview || preview.id !== m.id) {
    log(`keep ${m.id}: refusing (${has("--fail-keep") ? "--fail-keep" : "not the preview"})`);
    ws.send(JSON.stringify({ t: "failed", id: m.id, error: "no such preview" }));
    return;
  }
  const kept = preview.file.replace(/test-/, "kept-");
  renameSync(preview.file, kept);
  if (m.draft) {
    const earlier = saved.get(m.draft);
    if (earlier) { rmSync(earlier, { force: true }); log(`replaced the earlier keep of draft ${m.draft}`); }
    saved.set(m.draft, kept);
  }
  preview = null;
  ws.send(JSON.stringify({ t: "kept", id: m.id }));
  log(`kept ${m.id} as ${kept}`);
}

connect();
