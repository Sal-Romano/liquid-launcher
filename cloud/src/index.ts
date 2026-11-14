import { DRAFT, MAX_BYTES, Tv, bearer, type Reply } from "./tv";

export { Tv };

export interface Env {
  TV: DurableObjectNamespace<Tv>;
  ASSETS: Fetcher;
  /** Google Maps Platform key (Geocoding + Maps Static), a Worker secret. Without it place and map answer 503. */
  GOOGLE_MAPS_KEY?: string;
}

/** /api/tv/<tvId>/<action>[/<photo id>[/keep]]; anything else under /api is a 404 that never wakes an object. */
const TV_ROUTE = /^\/api\/tv\/([a-z2-7]{16})\/(socket|live|status|wallpapers|place|map)(?:\/([a-z0-9]{8,40})(\/keep)?)?$/;
const NO_STORE = { "Cache-Control": "no-store" };

export default {
  async fetch(request, env): Promise<Response> {
    const url = new URL(request.url);
    const route = TV_ROUTE.exec(url.pathname);
    if (!route) {
      if (url.pathname.startsWith("/api/")) return json({ status: 404, body: { error: "not found" } });
      return env.ASSETS.fetch(request);
    }
    const [, tvId, action, photoId, keep] = route;
    const tv = env.TV.get(env.TV.idFromName(tvId));
    const method = request.method;

    // WebSocket upgrades have to go to the object as requests.
    if ((action === "socket" || action === "live") && !photoId && method === "GET") return tv.fetch(request);

    if (action === "status" && !photoId && method === "GET") return json(await tv.status(bearer(request)));

    if (action === "wallpapers" && !photoId && method === "POST") {
      // Refuse an oversized photo before reading it; the object checks the key and the rest.
      if (Number(request.headers.get("Content-Length") ?? 0) > MAX_BYTES) {
        return json({ status: 413, body: { error: "that photo is over 20 MB" } });
      }
      const body = await request.arrayBuffer();
      if (body.byteLength > MAX_BYTES) return json({ status: 413, body: { error: "that photo is over 20 MB" } });
      const show = request.headers.get("X-Show") !== "later";
      const mode = request.headers.get("X-Mode") === "test" ? "test" : "keep";
      const draft = request.headers.get("X-Draft");
      if (draft !== null && !DRAFT.test(draft)) return json({ status: 400, body: { error: "bad X-Draft" } });
      const meta = cleanMeta(request.headers.get("X-Meta"));
      return json(await tv.receive(bearer(request), body, request.headers.get("X-From"), show, mode, draft, meta));
    }

    // Where a photo was taken, and a map of it: for the TV (its secret) or a phone (the send key).
    if (action === "place" && !photoId && method === "GET") {
      const p = url.searchParams;
      return json(await tv.place(bearer(request), number(p.get("lat")), number(p.get("lon")), p.get("q")));
    }
    if (action === "map" && !photoId && method === "GET") {
      const p = url.searchParams;
      const map = await tv.map(bearer(request), number(p.get("lat")), number(p.get("lon")), number(p.get("zoom")), p.get("size") === "small");
      if (!("bytes" in map)) return json(map);
      return new Response(map.bytes, { headers: { "Content-Type": map.type, "Cache-Control": "private, max-age=86400" } });
    }

    if (action === "wallpapers" && photoId && keep && method === "POST") return json(await tv.keep(bearer(request), photoId));

    if (action === "wallpapers" && photoId && !keep && method === "GET") {
      const photo = await tv.photo(bearer(request), photoId);
      if (!("bytes" in photo)) return json(photo);
      return new Response(photo.bytes, { headers: { "Content-Type": photo.type, ...NO_STORE } });
    }

    return json({ status: 405, body: { error: "method not allowed" } });
  },
} satisfies ExportedHandler<Env>;

/**
 * The photo details a phone sends in X-Meta (URL-encoded JSON), kept to known fields of the right
 * type and size: when it was taken (EXIF local time, with its offset when the camera wrote one),
 * where (degrees), and the camera. Anything else, or anything malformed, is dropped.
 */
function cleanMeta(raw: string | null): string | null {
  if (!raw || raw.length > 2000) return null;
  let m: Record<string, unknown>;
  try {
    m = JSON.parse(decodeURIComponent(raw));
  } catch {
    return null;
  }
  if (!m || typeof m !== "object") return null;
  const out: Record<string, unknown> = {};
  if (typeof m.takenAt === "string" && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?([+-]\d{2}:\d{2}|Z)?$/.test(m.takenAt)) out.takenAt = m.takenAt;
  const lat = Number(m.lat);
  const lon = Number(m.lon);
  if (typeof m.lat === "number" && typeof m.lon === "number" && Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat === 0 && lon === 0)) {
    out.lat = Math.round(lat * 1e5) / 1e5;
    out.lon = Math.round(lon * 1e5) / 1e5;
  }
  if (typeof m.camera === "string") {
    const camera = m.camera.replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, 48);
    if (camera) out.camera = camera;
  }
  return Object.keys(out).length ? JSON.stringify(out) : null;
}

function number(raw: string | null): number | null {
  if (raw === null || raw.trim() === "") return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

function json(reply: Reply): Response {
  return Response.json(reply.body, { status: reply.status, headers: NO_STORE });
}
