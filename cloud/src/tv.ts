import { DurableObject } from "cloudflare:workers";
import type { Env } from "./index";

/** SQLite rows top out at 2 MB, so a photo is stored in 1 MiB chunks. */
const CHUNK = 1 << 20;
/** Photos waiting for a TV that is off; more than this is someone holding the button down. */
const MAX_WAITING = 30;
/** Log rows kept for the phone's "sent" list (their bytes are gone long before). */
const KEEP_LOG = 40;
/** A photo the TV never collected is given up after two weeks. */
const PENDING_TTL = 14 * 24 * 3600_000;
const SWEEP_EVERY = 24 * 3600_000;
/** The TV pings every 25 s; a TV not heard from for this long is offline even if its socket lingers. */
const ONLINE_WITHIN = 70_000;

const KEY = /^[A-Za-z0-9_-]{16,128}$/;
/** One per photo chosen on the phone: a later "keep" with the same draft replaces the earlier one. */
export const DRAFT = /^[a-z0-9]{8,32}$/;
const NO_STORE = { "Cache-Control": "no-store" };

/**
 * queued → delivered → shown, then for a test → kept (Use this) — or failed at any point.
 * For a keep, delivered means saved on the TV; for a test, only that the TV has it.
 */
type State = "queued" | "delivered" | "shown" | "kept" | "failed";
const RANK: Record<State, number> = { queued: 0, delivered: 1, shown: 2, kept: 3, failed: 4 };

/** keep: a wallpaper, saved on the TV. test: shown on the TV for a look, not saved, never queued. */
export type Mode = "keep" | "test";

type Upload = {
  id: string;
  at: number;
  sender: string | null;
  type: string;
  bytes: number;
  /** 1: put it up as soon as it lands; 0: only add it to the rotation. */
  show: number;
  state: State;
  /** Set with "shown": 1 when the home screen was in front, 0 when the TV was in another app. */
  visible: number | null;
  mode: Mode;
  draft: string | null;
};

/** What a hibernating socket remembers: when its client last said something other than "ping". */
type Attachment = { at: number };

type Access = "ok" | "unknown" | "denied";

/** An RPC answer the Worker turns into a JSON response. */
export type Reply = { status: number; body: Record<string, unknown> };
export type Photo = { type: string; bytes: ArrayBuffer };

export const MAX_BYTES = 20 << 20;

/**
 * One TV: its keys, the photos on their way to it, and the sockets of the TV and of any phone
 * watching. Sockets use the hibernation API, so a TV that is connected but idle costs nothing.
 */
export class Tv extends DurableObject<Env> {
  private readonly sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
    this.sql.exec(`CREATE TABLE IF NOT EXISTS upload (
      id TEXT PRIMARY KEY, at INTEGER NOT NULL, sender TEXT, type TEXT NOT NULL, bytes INTEGER NOT NULL,
      show INTEGER NOT NULL, state TEXT NOT NULL, visible INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS chunk (
      id TEXT NOT NULL, seq INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY (id, seq))`);
    // Columns added after the first deploy; objects made before them gain them here.
    const columns = new Set(this.sql.exec<{ name: string }>("SELECT name FROM pragma_table_info('upload')").toArray().map((c) => c.name));
    if (!columns.has("mode")) this.sql.exec("ALTER TABLE upload ADD COLUMN mode TEXT NOT NULL DEFAULT 'keep'");
    if (!columns.has("draft")) this.sql.exec("ALTER TABLE upload ADD COLUMN draft TEXT");
    // Both kinds of client ping every 25 s; the runtime answers without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  /** The two WebSocket upgrades; everything else comes in over RPC (see index.ts). */
  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    // /api/tv/<tvId>/<action>; the Worker has already checked the shape.
    const action = url.pathname.split("/")[4];
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return fail(426, "expected a WebSocket upgrade");
    if (action === "socket") return this.tvSocket(request);
    if (action === "live") return this.phoneSocket(url.searchParams.get("k"));
    return fail(404, "not found");
  }

  // ---- the TV ----

  private async tvSocket(request: Request): Promise<Response> {
    const secret = bearer(request);
    const sendKey = request.headers.get("X-Send-Key");
    if (!secret || !KEY.test(secret) || secret.length < 32) return fail(401, "missing TV secret");
    if (!sendKey || !KEY.test(sendKey)) return fail(400, "missing send key");
    const hash = await sha256(secret);
    const claimed = this.meta("secret");
    if (claimed === null) {
      // First connection with this id: it is the TV's from now on.
      this.setMeta("secret", hash);
      this.setMeta("since", String(Date.now()));
    } else if (!same(claimed, hash)) {
      return fail(401, "wrong TV secret");
    }
    const name = label(request.headers.get("X-TV-Name"));
    if (name) this.setMeta("name", name);
    await this.useSendKey(sendKey);

    for (const old of this.ctx.getWebSockets("tv")) {
      sendQuietly(old, JSON.stringify({ t: "replaced" }));
      closeQuietly(old, 4000, "replaced by a newer connection");
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, ["tv"]);
    server.serializeAttachment({ at: Date.now() } satisfies Attachment);
    server.send(JSON.stringify({ t: "hello", v: 2 }));
    // Everything kept while the TV was away. All of it joins the rotation; only the newest goes
    // up at once, so a TV coming back on does not flick through a backlog. A test that never got
    // there is stale by now: dropped, not shown late.
    const waiting = this.waiting();
    for (const u of waiting.filter((w) => w.mode === "test")) this.settle(u.id, "failed");
    const keeps = waiting.filter((w) => w.mode === "keep");
    keeps.forEach((u, i) => server.send(JSON.stringify(offer(u, i === keeps.length - 1 && u.show === 1))));
    this.toPhones({ t: "presence", online: true });
    return new Response(null, { status: 101, webSocket: client });
  }

  /** The TV collects a photo's bytes. */
  async photo(secret: string | null, id: string): Promise<Reply | Photo> {
    if (!(await this.isTv(secret))) return reply(401, "wrong TV secret");
    const u = this.upload(id);
    if (!u) return reply(404, "no such photo");
    const parts = this.sql.exec<{ data: ArrayBuffer }>("SELECT data FROM chunk WHERE id = ? ORDER BY seq", id).toArray();
    if (parts.length === 0) return reply(410, "that photo was already collected");
    const bytes = new Uint8Array(u.bytes);
    let off = 0;
    for (const p of parts) {
      bytes.set(new Uint8Array(p.data), off);
      off += p.data.byteLength;
    }
    return { type: u.type, bytes: bytes.buffer };
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string" || !this.ctx.getTags(ws).includes("tv")) return;
    let m: Record<string, unknown>;
    try {
      m = JSON.parse(message);
    } catch {
      return;
    }
    if (m === null || typeof m !== "object") return;
    ws.serializeAttachment({ at: Date.now() } satisfies Attachment);
    const id = typeof m.id === "string" ? m.id : "";
    switch (m.t) {
      case "got":
        this.settle(id, "delivered");
        break;
      case "shown":
        this.settle(id, "shown", m.visible === false ? 0 : 1);
        break;
      case "kept":
        this.settle(id, "kept");
        break;
      case "check":
        // Answered by this code, not the runtime: a socket that outlived a deploy still gets its
        // pongs from the runtime but never this, which is how the TV knows to reconnect.
        sendQuietly(ws, JSON.stringify({ t: "ok" }));
        break;
      case "failed":
        this.settle(id, "failed");
        break;
      case "key":
        if (typeof m.sendKey === "string" && KEY.test(m.sendKey)) await this.useSendKey(m.sendKey);
        break;
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    // Finish the closing handshake. A client that closed without a status arrives as 1005 (and a
    // dropped one as 1006), which cannot be sent back; answer those with a plain 1000, or the
    // client waits for a close frame that never comes.
    closeQuietly(ws, code === 1005 || code === 1006 || code === 1015 ? 1000 : code, reason);
    if (this.ctx.getTags(ws).includes("tv")) this.toPhones({ t: "presence", online: this.tvOnline(ws) });
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    if (this.ctx.getTags(ws).includes("tv")) this.toPhones({ t: "presence", online: this.tvOnline(ws) });
  }

  // ---- phones ----

  private async phoneSocket(key: string | null): Promise<Response> {
    const [client, server] = Object.values(new WebSocketPair());
    const access = await this.phoneAccess(key);
    if (access !== "ok") {
      // A browser never sees the status of a refused upgrade, but it does see a close code.
      server.accept();
      if (access === "unknown") server.close(4404, "no TV with that code");
      else server.close(4401, "this link was reset on the TV");
      return new Response(null, { status: 101, webSocket: client });
    }
    this.ctx.acceptWebSocket(server, ["phone"]);
    server.send(JSON.stringify({ t: "hello", ...this.summary() }));
    return new Response(null, { status: 101, webSocket: client });
  }

  /** What the phone's socket says on connect, for a phone without one. */
  async status(key: string | null): Promise<Reply> {
    const access = await this.phoneAccess(key);
    if (access !== "ok") return denied(access);
    return { status: 200, body: this.summary() };
  }

  /**
   * A phone sends a photo. The Worker has read the body (and refused anything over the limit
   * before reading); [from] is the raw X-From header, [show] false for X-Show: later.
   */
  async receive(key: string | null, body: ArrayBuffer, from: string | null, show: boolean, mode: Mode, draft: string | null): Promise<Reply> {
    const access = await this.phoneAccess(key);
    if (access !== "ok") return denied(access);
    // A test is for looking at now; it never waits for a TV that is off.
    if (mode === "test" && !this.tvOnline()) return reply(409, "the TV is off");
    const waiting = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM upload WHERE state = 'queued'").one().n;
    if (waiting >= MAX_WAITING) return reply(429, `the TV already has ${waiting} photos waiting for it`);
    if (body.byteLength === 0) return reply(400, "no photo in the request");
    if (body.byteLength > MAX_BYTES) return reply(413, "that photo is over 20 MB");
    const type = sniff(new Uint8Array(body, 0, Math.min(16, body.byteLength)));
    if (!type) return reply(415, "not a JPEG, PNG or WebP image");

    const u: Upload = {
      id: newId(),
      at: Date.now(),
      sender: label(from),
      type,
      bytes: body.byteLength,
      show: show ? 1 : 0,
      state: "queued",
      visible: null,
      mode,
      draft,
    };
    this.ctx.storage.transactionSync(() => {
      this.sql.exec(
        "INSERT INTO upload (id, at, sender, type, bytes, show, state, mode, draft) VALUES (?, ?, ?, ?, ?, ?, 'queued', ?, ?)",
        u.id, u.at, u.sender, u.type, u.bytes, u.show, u.mode, u.draft,
      );
      for (let seq = 0, off = 0; off < body.byteLength; seq++, off += CHUNK) {
        this.sql.exec("INSERT INTO chunk (id, seq, data) VALUES (?, ?, ?)", u.id, seq, body.slice(off, off + CHUNK));
      }
      this.sql.exec(
        "DELETE FROM upload WHERE state != 'queued' AND id NOT IN (SELECT id FROM upload ORDER BY at DESC LIMIT ?)",
        KEEP_LOG,
      );
    });
    // An earlier test of the same photo that the TV has not collected yet is out of date.
    if (mode === "test" && draft) {
      const older = this.sql
        .exec<{ id: string }>("SELECT id FROM upload WHERE draft = ? AND mode = 'test' AND state = 'queued' AND id != ?", draft, u.id)
        .toArray();
      for (const o of older) this.settle(o.id, "failed");
    }
    const text = JSON.stringify(offer(u, u.show === 1));
    for (const tv of this.ctx.getWebSockets("tv")) sendQuietly(tv, text);
    this.toPhones({ t: "upload", ...publicUpload(u) });
    await this.ensureSweep();
    return { status: 201, body: { id: u.id, state: u.state, online: this.tvOnline() } };
  }

  /**
   * Use this, for a test that is on the TV: the TV saves the copy it already has, so nothing is
   * sent again. It answers kept, or failed if it no longer has it (the phone then sends a keep).
   */
  async keep(key: string | null, id: string): Promise<Reply> {
    const access = await this.phoneAccess(key);
    if (access !== "ok") return denied(access);
    const u = this.upload(id);
    if (!u) return reply(404, "no such photo");
    if (u.mode !== "test") return reply(409, "that photo is already kept");
    if (u.state === "kept") return { status: 200, body: { id, state: u.state } };
    if (u.state !== "delivered" && u.state !== "shown") return reply(409, "that test is not on the TV");
    if (!this.tvOnline()) return reply(409, "the TV is off");
    const text = JSON.stringify({ t: "keep", id, draft: u.draft });
    for (const tv of this.ctx.getWebSockets("tv")) sendQuietly(tv, text);
    return { status: 202, body: { id, state: u.state } };
  }

  // ---- housekeeping ----

  async alarm(): Promise<void> {
    const stale = this.sql
      .exec<{ id: string }>("SELECT id FROM upload WHERE state = 'queued' AND at < ?", Date.now() - PENDING_TTL)
      .toArray();
    for (const { id } of stale) this.settle(id, "failed");
    this.sql.exec("DELETE FROM chunk WHERE id NOT IN (SELECT id FROM upload WHERE state = 'queued')");
    if (this.waiting().length > 0) await this.ctx.storage.setAlarm(Date.now() + SWEEP_EVERY);
  }

  private async ensureSweep(): Promise<void> {
    if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(Date.now() + SWEEP_EVERY);
  }

  /** Moves a photo forward (queued → delivered → shown → kept, or failed); never back. Its bytes go at the first step. */
  private settle(id: string, state: State, visible: number | null = null): void {
    const u = this.upload(id);
    if (!u || u.state === "kept" || u.state === "failed" || RANK[state] <= RANK[u.state]) return;
    const seen = visible ?? u.visible;
    this.sql.exec("UPDATE upload SET state = ?, visible = ? WHERE id = ?", state, seen, id);
    this.sql.exec("DELETE FROM chunk WHERE id = ?", id);
    this.toPhones({ t: "upload", ...publicUpload({ ...u, state, visible: seen }) });
  }

  private async useSendKey(key: string): Promise<void> {
    const hash = await sha256(key);
    const old = this.meta("send");
    if (old === hash) return;
    this.setMeta("send", hash);
    if (old !== null) {
      // Said as a message first: a close the object starts on a hibernated socket can take
      // seconds to surface as a close event on the client, the message arrives at once.
      for (const phone of this.ctx.getWebSockets("phone")) {
        sendQuietly(phone, JSON.stringify({ t: "reset" }));
        closeQuietly(phone, 4001, "this link was reset on the TV");
      }
    }
  }

  private async isTv(secret: string | null): Promise<boolean> {
    const want = this.meta("secret");
    return want !== null && !!secret && KEY.test(secret) && same(want, await sha256(secret));
  }

  private async phoneAccess(key: string | null): Promise<Access> {
    const want = this.meta("send");
    if (want === null) return "unknown";
    if (!key || !KEY.test(key)) return "denied";
    return same(want, await sha256(key)) ? "ok" : "denied";
  }

  private summary() {
    const uploads = this.sql.exec<Upload>("SELECT * FROM upload ORDER BY at DESC LIMIT 12").toArray().map(publicUpload);
    return { online: this.tvOnline(), name: this.meta("name"), uploads };
  }

  private tvOnline(leaving?: WebSocket): boolean {
    const now = Date.now();
    return this.ctx
      .getWebSockets("tv")
      .some((ws) => ws !== leaving && ws.readyState === WebSocket.READY_STATE_OPEN && now - this.heardAt(ws) < ONLINE_WITHIN);
  }

  private heardAt(ws: WebSocket): number {
    const pinged = this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime() ?? 0;
    const said = (ws.deserializeAttachment() as Attachment | null)?.at ?? 0;
    return Math.max(pinged, said);
  }

  private toPhones(message: object): void {
    const text = JSON.stringify(message);
    for (const ws of this.ctx.getWebSockets("phone")) sendQuietly(ws, text);
  }

  private upload(id: string): Upload | null {
    return this.sql.exec<Upload>("SELECT * FROM upload WHERE id = ?", id).toArray()[0] ?? null;
  }

  private waiting(): Upload[] {
    return this.sql.exec<Upload>("SELECT * FROM upload WHERE state = 'queued' ORDER BY at").toArray();
  }

  private meta(k: string): string | null {
    return this.sql.exec<{ v: string }>("SELECT v FROM meta WHERE k = ?", k).toArray()[0]?.v ?? null;
  }

  private setMeta(k: string, v: string): void {
    this.sql.exec("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v", k, v);
  }
}

function offer(u: Upload, show: boolean) {
  return { t: "wallpaper", id: u.id, type: u.type, bytes: u.bytes, from: u.sender, at: u.at, show, mode: u.mode, draft: u.draft };
}

function publicUpload(u: Upload) {
  return {
    id: u.id, at: u.at, from: u.sender, state: u.state, visible: u.visible === null ? null : u.visible === 1,
    mode: u.mode, draft: u.draft,
  };
}

function fail(status: number, error: string): Response {
  return Response.json({ error }, { status, headers: NO_STORE });
}

function reply(status: number, error: string): Reply {
  return { status, body: { error } };
}

function denied(access: Exclude<Access, "ok">): Reply {
  return access === "unknown" ? reply(404, "no TV with that code") : reply(401, "this link was reset on the TV");
}

/** The secret from an Authorization: Bearer header. */
export function bearer(request: Request): string | null {
  const h = request.headers.get("Authorization");
  return h?.startsWith("Bearer ") ? h.slice(7).trim() : null;
}

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function same(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  return x.byteLength === y.byteLength && crypto.subtle.timingSafeEqual(x, y);
}

/** A display label from a header: URL-decoded, control characters dropped, at most 40 characters. */
function label(raw: string | null): string | null {
  if (!raw) return null;
  let text = raw;
  try {
    text = decodeURIComponent(raw);
  } catch {
    // Not encoded; use as is.
  }
  text = text.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 40);
  return text || null;
}

function sniff(b: Uint8Array): string | null {
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "image/jpeg";
  if (b.length >= 8 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return "image/png";
  const ascii = (from: number, to: number) => String.fromCharCode(...b.subarray(from, to));
  if (b.length >= 12 && ascii(0, 4) === "RIFF" && ascii(8, 12) === "WEBP") return "image/webp";
  return null;
}

/** Time-ordered then random, lower-case base 36: sorts by arrival and is safe in a file name. */
function newId(): string {
  const rand = crypto.getRandomValues(new Uint8Array(8));
  return Date.now().toString(36) + [...rand].map((b) => (b % 36).toString(36)).join("");
}

function sendQuietly(ws: WebSocket, text: string): void {
  try {
    ws.send(text);
  } catch {
    // Closing or gone; its close event tidies up.
  }
}

function closeQuietly(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed, or a code that cannot be sent (1005, 1006).
  }
}
