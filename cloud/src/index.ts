import { DRAFT, MAX_BYTES, Tv, bearer, type Reply } from "./tv";

export { Tv };

export interface Env {
  TV: DurableObjectNamespace<Tv>;
  ASSETS: Fetcher;
}

/** /api/tv/<tvId>/<action>[/<photo id>[/keep]]; anything else under /api is a 404 that never wakes an object. */
const TV_ROUTE = /^\/api\/tv\/([a-z2-7]{16})\/(socket|live|status|wallpapers)(?:\/([a-z0-9]{8,40})(\/keep)?)?$/;
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
      return json(await tv.receive(bearer(request), body, request.headers.get("X-From"), show, mode, draft));
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

function json(reply: Reply): Response {
  return Response.json(reply.body, { status: reply.status, headers: NO_STORE });
}
