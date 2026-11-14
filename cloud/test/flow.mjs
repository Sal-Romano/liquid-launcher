// End-to-end check of the relay against `wrangler dev`: a fake TV and a fake phone.
//   node test/flow.mjs [http://localhost:8787]
// Covers: claiming an id, a live push, the bytes round trip, got/shown reaching the phone, a
// backlog while the TV is away (only the newest shown), a wrong secret, a key reset, junk uploads.

import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import http from "node:http";

const base = process.argv[2] ?? "http://localhost:8787";
const wsBase = base.replace(/^http/, "ws");

const b32 = (n) => [...randomBytes(n)].map((b) => "abcdefghijklmnopqrstuvwxyz234567"[b % 32]).join("");
const b64 = (n) => randomBytes(n).toString("base64url");

const tvId = b32(16);
const secret = b64(32);
let sendKey = b64(16);

// A tiny valid JPEG header is enough: the relay sniffs the magic bytes, it never decodes.
const jpeg = (size) => {
  const b = randomBytes(size);
  b[0] = 0xff; b[1] = 0xd8; b[2] = 0xff;
  return b;
};

/** A socket that queues messages so a test can await the next one of a kind. */
function client(url, headers) {
  const ws = new WebSocket(url, headers ? { headers } : undefined);
  const inbox = [];
  const waiters = [];
  let closed = null;
  ws.addEventListener("message", (e) => {
    const data = typeof e.data === "string" ? e.data : "";
    if (data === "pong") return;
    const m = JSON.parse(data);
    const i = waiters.findIndex((w) => w.match(m));
    if (i >= 0) waiters.splice(i, 1)[0].resolve(m);
    else inbox.push(m);
  });
  ws.addEventListener("close", (e) => {
    closed = { code: e.code, reason: e.reason };
    for (const w of waiters.splice(0)) w.reject(new Error(`closed ${e.code} ${e.reason} while waiting`));
  });
  return {
    ws,
    get closed() { return closed; },
    opened: new Promise((resolve, reject) => {
      ws.addEventListener("open", resolve, { once: true });
      ws.addEventListener("error", reject, { once: true });
    }),
    next(match, ms = 5000) {
      const i = inbox.findIndex(match);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((resolve, reject) => {
        const w = { match, resolve, reject };
        waiters.push(w);
        setTimeout(() => { const j = waiters.indexOf(w); if (j >= 0) { waiters.splice(j, 1); reject(new Error("timed out waiting")); } }, ms);
      });
    },
    send(obj) { ws.send(typeof obj === "string" ? obj : JSON.stringify(obj)); },
    close() { ws.close(); },
    closedWith(ms = 15000) {
      if (closed) return Promise.resolve(closed);
      return new Promise((resolve, reject) => {
        ws.addEventListener("close", (e) => resolve({ code: e.code, reason: e.reason }), { once: true });
        setTimeout(() => reject(new Error("socket did not close")), ms);
      });
    },
  };
}

const tvSocket = (key = sendKey, s = secret) =>
  client(`${wsBase}/api/tv/${tvId}/socket`, { Authorization: `Bearer ${s}`, "X-Send-Key": key, "X-TV-Name": encodeURIComponent("Living room") });
const phoneSocket = (key = sendKey) => client(`${wsBase}/api/tv/${tvId}/live?k=${key}`);
const upload = (body, extra = {}, key = sendKey) =>
  fetch(`${base}/api/tv/${tvId}/wallpapers`, {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "image/jpeg", "X-From": encodeURIComponent("Test phone"), ...extra },
    body,
  });
const fetchBytes = (id, s = secret) => fetch(`${base}/api/tv/${tvId}/wallpapers/${id}`, { headers: { Authorization: `Bearer ${s}` } });

const step = (name) => console.log(`- ${name}`);

// Before any TV connects, the id is unknown.
step("unknown TV: upload is 404, phone socket closes 4404");
assert.equal((await upload(jpeg(100))).status, 404);
{
  const p = phoneSocket();
  assert.equal((await p.closedWith()).code, 4404);
}

step("TV connects and claims its id");
let tv = tvSocket();
await tv.opened;
await tv.next((m) => m.t === "hello");

step("phone sees the TV online");
const phone = phoneSocket();
await phone.opened;
const hello = await phone.next((m) => m.t === "hello");
assert.equal(hello.online, true);
assert.equal(hello.name, "Living room");

step("check is answered with ok by the object's own code");
tv.send({ t: "check" });
await tv.next((m) => m.t === "ok");

step("ping is answered with pong");
{
  const got = new Promise((resolve) => tv.ws.addEventListener("message", (e) => e.data === "pong" && resolve(), { once: false }));
  tv.send("ping");
  await Promise.race([got, new Promise((_, r) => setTimeout(() => r(new Error("no pong")), 3000))]);
}

step("upload is pushed to the TV at once; bytes round-trip intact (3.5 MB, several chunks)");
const photo = jpeg(3_500_000);
let t0 = performance.now();
let res = await upload(photo);
assert.equal(res.status, 201);
const sent = await res.json();
assert.equal(sent.online, true);
const offer = await tv.next((m) => m.t === "wallpaper" && m.id === sent.id);
console.log(`  pushed ${Math.round(performance.now() - t0)} ms after the upload began`);
assert.equal(offer.show, true);
assert.equal(offer.from, "Test phone");
assert.equal(offer.bytes, photo.length);
await phone.next((m) => m.t === "upload" && m.id === sent.id && m.state === "queued");
res = await fetchBytes(sent.id);
assert.equal(res.status, 200);
assert.equal(res.headers.get("content-type"), "image/jpeg");
assert.ok(Buffer.from(await res.arrayBuffer()).equals(photo), "bytes differ");

step("the wrong secret cannot fetch it");
assert.equal((await fetchBytes(sent.id, b64(32))).status, 401);

step("got and shown reach the phone; the bytes are gone after got");
tv.send({ t: "got", id: sent.id });
await phone.next((m) => m.t === "upload" && m.id === sent.id && m.state === "delivered");
tv.send({ t: "shown", id: sent.id, visible: true });
const shown = await phone.next((m) => m.t === "upload" && m.id === sent.id && m.state === "shown");
assert.equal(shown.visible, true);
assert.equal((await fetchBytes(sent.id)).status, 410);

step("status agrees");
{
  const s = await (await fetch(`${base}/api/tv/${tvId}/status`, { headers: { Authorization: `Bearer ${sendKey}` } })).json();
  assert.equal(s.online, true);
  assert.equal(s.uploads[0].id, sent.id);
  assert.equal(s.uploads[0].state, "shown");
}

step("TV goes away: phone hears it; three uploads wait");
tv.close();
await tv.closedWith();
const gone = await phone.next((m) => m.t === "presence");
assert.equal(gone.online, false);
const backlog = [];
for (let i = 0; i < 3; i++) {
  const r = await upload(jpeg(20_000 + i));
  assert.equal(r.status, 201);
  const j = await r.json();
  assert.equal(j.online, false);
  backlog.push(j.id);
}

step("TV comes back: all three offered in order, only the newest to show");
tv = tvSocket();
await tv.opened;
await tv.next((m) => m.t === "hello");
const offers = [];
for (const id of backlog) offers.push(await tv.next((m) => m.t === "wallpaper" && m.id === id));
assert.deepEqual(offers.map((o) => o.show), [false, false, true]);
assert.equal((await phone.next((m) => m.t === "presence")).online, true);
for (const id of backlog) tv.send({ t: "got", id });
await phone.next((m) => m.t === "upload" && m.id === backlog[2] && m.state === "delivered");

step("a second TV connection with the right secret replaces the first (4000)");
{
  const again = tvSocket();
  await again.opened;
  await tv.next((m) => m.t === "replaced");
  assert.equal((await tv.closedWith()).code, 4000);
  tv = again;
  await tv.next((m) => m.t === "hello");
}

step("a TV connection with the wrong secret is refused (401 on the upgrade)");
{
  // fetch() will not send an Upgrade header, so ask over plain HTTP.
  const status = await new Promise((resolve, reject) => {
    const u = new URL(`${base}/api/tv/${tvId}/socket`);
    const req = http.request({
      host: u.hostname, port: u.port, path: u.pathname,
      headers: { Upgrade: "websocket", Connection: "Upgrade", "Sec-WebSocket-Key": randomBytes(16).toString("base64"), "Sec-WebSocket-Version": "13", Authorization: `Bearer ${b64(32)}`, "X-Send-Key": sendKey },
    });
    req.on("response", (r) => { r.resume(); resolve(r.statusCode); });
    req.on("upgrade", (r, socket) => { socket.destroy(); resolve(101); });
    req.on("error", reject);
    req.end();
  });
  assert.equal(status, 401);
}

step("junk is refused: not an image 415, empty 400, wrong key 401");
assert.equal((await upload(Buffer.from("hello, world, not an image"))).status, 415);
assert.equal((await upload(Buffer.alloc(0))).status, 400);
assert.equal((await upload(jpeg(100), {}, b64(16))).status, 401);

step("X-Show: later is offered with show false");
{
  const r = await upload(jpeg(5000), { "X-Show": "later" });
  const j = await r.json();
  const o = await tv.next((m) => m.t === "wallpaper" && m.id === j.id);
  assert.equal(o.show, false);
  tv.send({ t: "got", id: j.id });
}

step("New link on the TV: the old phone is closed with 4001 and its key stops working");
const oldKey = sendKey;
sendKey = b64(16);
tv.send({ t: "key", sendKey });
await phone.next((m) => m.t === "reset", 2000);
assert.equal((await phone.closedWith()).code, 4001);
assert.equal((await upload(jpeg(100), {}, oldKey)).status, 401);
{
  const p = phoneSocket(oldKey);
  assert.equal((await p.closedWith()).code, 4401);
  const fresh = phoneSocket();
  await fresh.opened;
  assert.equal((await fresh.next((m) => m.t === "hello")).online, true);
  fresh.close();
}

step("failed is reported to the phone");
{
  const watcher = phoneSocket();
  await watcher.opened;
  await watcher.next((m) => m.t === "hello");
  const j = await (await upload(jpeg(4000))).json();
  await tv.next((m) => m.t === "wallpaper" && m.id === j.id);
  tv.send({ t: "failed", id: j.id, error: "could not decode" });
  await watcher.next((m) => m.t === "upload" && m.id === j.id && m.state === "failed");
  watcher.close();
}

step("a test while the TV is away is refused (409) and nothing waits");
{
  tv.close();
  await tv.closedWith();
  const r = await upload(jpeg(3000), { "X-Mode": "test", "X-Draft": "drafttest01" });
  assert.equal(r.status, 409);
  tv = tvSocket();
  await tv.opened;
  await tv.next((m) => m.t === "hello");
}

step("test: offered with mode and draft; got/shown; Use this keeps it without a new upload");
{
  const watcher = phoneSocket();
  await watcher.opened;
  await watcher.next((m) => m.t === "hello");
  const draft = "draftaaaa01";
  const j = await (await upload(jpeg(6000), { "X-Mode": "test", "X-Draft": draft })).json();
  const o = await tv.next((m) => m.t === "wallpaper" && m.id === j.id);
  assert.equal(o.mode, "test");
  assert.equal(o.draft, draft);
  // Use this before the TV has it is refused.
  const early = await fetch(`${base}/api/tv/${tvId}/wallpapers/${j.id}/keep`, { method: "POST", headers: { Authorization: `Bearer ${sendKey}` } });
  assert.equal(early.status, 409);
  tv.send({ t: "got", id: j.id });
  tv.send({ t: "shown", id: j.id, visible: true });
  await watcher.next((m) => m.t === "upload" && m.id === j.id && m.state === "shown" && m.mode === "test");
  const k = await fetch(`${base}/api/tv/${tvId}/wallpapers/${j.id}/keep`, { method: "POST", headers: { Authorization: `Bearer ${sendKey}` } });
  assert.equal(k.status, 202);
  const ask = await tv.next((m) => m.t === "keep" && m.id === j.id);
  assert.equal(ask.draft, draft);
  tv.send({ t: "kept", id: j.id });
  await watcher.next((m) => m.t === "upload" && m.id === j.id && m.state === "kept");
  // Again: already kept, 200.
  assert.equal((await fetch(`${base}/api/tv/${tvId}/wallpapers/${j.id}/keep`, { method: "POST", headers: { Authorization: `Bearer ${sendKey}` } })).status, 200);

  step("a keep cannot be kept again (409); a newer test supersedes an older one still waiting");
  const kj = await (await upload(jpeg(5000), { "X-Draft": draft })).json();
  const ko = await tv.next((m) => m.t === "wallpaper" && m.id === kj.id);
  assert.equal(ko.mode, "keep");
  assert.equal((await fetch(`${base}/api/tv/${tvId}/wallpapers/${kj.id}/keep`, { method: "POST", headers: { Authorization: `Bearer ${sendKey}` } })).status, 409);
  tv.send({ t: "got", id: kj.id });
  const t1 = await (await upload(jpeg(4000), { "X-Mode": "test", "X-Draft": "draftbbbb02" })).json();
  const t2 = await (await upload(jpeg(4001), { "X-Mode": "test", "X-Draft": "draftbbbb02" })).json();
  await watcher.next((m) => m.t === "upload" && m.id === t1.id && m.state === "failed");
  await tv.next((m) => m.t === "wallpaper" && m.id === t2.id);
  assert.equal((await fetchBytes(t1.id)).status, 410);
  tv.send({ t: "got", id: t2.id });

  step("a test still waiting when the TV reconnects is dropped, not shown late");
  const t3 = await (await upload(jpeg(4002), { "X-Mode": "test", "X-Draft": "draftcccc03" })).json();
  const again = tvSocket();
  await again.opened;
  await again.next((m) => m.t === "hello");
  tv = again;
  await watcher.next((m) => m.t === "upload" && m.id === t3.id && m.state === "failed");
  watcher.close();
}

step("X-Meta reaches the TV cleaned: known fields only, bad values dropped");
{
  const meta = { takenAt: "2024-07-14T18:22:05+02:00", lat: 38.7223, lon: -9.1393, camera: "iPhone 15 Pro", evil: "<script>", extra: 1 };
  const j = await (await upload(jpeg(3000), { "X-Meta": encodeURIComponent(JSON.stringify(meta)) })).json();
  const o = await tv.next((m) => m.t === "wallpaper" && m.id === j.id);
  assert.deepEqual(o.meta, { takenAt: "2024-07-14T18:22:05+02:00", lat: 38.7223, lon: -9.1393, camera: "iPhone 15 Pro" });
  tv.send({ t: "got", id: j.id });
  const bad = await (await upload(jpeg(3001), { "X-Meta": encodeURIComponent(JSON.stringify({ takenAt: "yesterday", lat: 200, lon: 5 })) })).json();
  const ob = await tv.next((m) => m.t === "wallpaper" && m.id === bad.id);
  assert.equal(ob.meta, null);
  tv.send({ t: "got", id: bad.id });
}

step("place and map: refused without this TV's secret or key (401); 503 or real answers with one");
{
  assert.equal((await fetch(`${base}/api/tv/${tvId}/place?lat=38.72&lon=-9.14`, { headers: { Authorization: `Bearer ${b64(16)}` } })).status, 401);
  const asTv = await fetch(`${base}/api/tv/${tvId}/place?lat=38.72&lon=-9.14`, { headers: { Authorization: `Bearer ${secret}` } });
  const asPhone = await fetch(`${base}/api/tv/${tvId}/place?q=${encodeURIComponent("aoraki mount cook")}`, { headers: { Authorization: `Bearer ${sendKey}` } });
  if (asTv.status === 503) {
    console.log("  (no GOOGLE_MAPS_KEY on this relay: place and map answer 503)");
    assert.equal(asPhone.status, 503);
  } else {
    assert.equal(asTv.status, 200);
    const p = await asTv.json();
    assert.equal(p.countryCode, "PT");
    console.log(`  ${p.name}, ${p.country}`);
    const q = await asPhone.json();
    assert.equal(q.countryCode, "NZ");
    console.log(`  "aoraki mount cook" -> ${q.name}, ${q.country} (${q.lat}, ${q.lon})`);
    const m = await fetch(`${base}/api/tv/${tvId}/map?lat=38.72&lon=-9.14&zoom=4`, { headers: { Authorization: `Bearer ${secret}` } });
    assert.equal(m.status, 200);
    assert.equal(m.headers.get("content-type"), "image/png");
    console.log(`  map: ${(await m.arrayBuffer()).byteLength} bytes`);
  }
}

step("a bad X-Draft is refused (400)");
assert.equal((await upload(jpeg(100), { "X-Draft": "NOT OK" })).status, 400);

step("unknown API paths are 404 without touching an object");
assert.equal((await fetch(`${base}/api/tv/NOT-AN-ID/status`)).status, 404);
assert.equal((await fetch(`${base}/api/nothing`)).status, 404);

tv.close();
console.log("ok");
process.exit(0);
