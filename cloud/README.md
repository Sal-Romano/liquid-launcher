# liquid-cloud: send a wallpaper from your phone

A Cloudflare Worker that sits between a phone and the launcher. The TV shows a QR code (Settings,
then Wallpapers). The phone opens this Worker's page, frames a photo for the TV and sends it. The
TV gets the photo a moment later, computes its glass and crossfades to it.

```
phone ──POST photo──▶ Worker ──▶ Durable Object "Tv" (one per TV) ──WebSocket push──▶ TV
  ▲                                   │  SQLite: keys, upload log, photo bytes          │
  └────── WebSocket: delivered/shown ─┘◀── got / shown ─────────────────────────────────┘
                                       ◀── GET the bytes ───────────────────────────────┘
```

- **Fast:** the TV keeps one WebSocket open to its Durable Object (hibernating, so an idle TV costs
  nothing), and a new photo is announced over it as soon as it is stored.
- **Nothing lost:** a photo kept while the TV is off waits in the object's SQLite storage (up to
  14 days). The TV collects everything when it reconnects, and only the newest is shown at once.
  A test is only for now: refused while the TV is off, never replayed.
- **Test, then use:** a test goes up on the TV without being saved (each new test replaces it, and
  the TV goes back to its photos after 10 minutes). Use this saves the test the TV already has, or
  sends the photo as a keep; a keep replaces the earlier keep of the same photo (`X-Draft`), so
  reframing never leaves old versions behind.
- **Nothing kept:** the bytes are deleted as soon as the TV says it has them. Only a short
  log stays (time, state, sender label) for the phone's "sent" list.

## Keys

The TV makes three random values on first run and keeps them in `files/phone.json`:

| name      | form                          | who has it                       |
|-----------|-------------------------------|----------------------------------|
| `tvId`    | 16 chars `[a-z2-7]` (80 bits) | the TV and the QR code           |
| `secret`  | 43 chars base64url (256 bits) | the TV only                      |
| `sendKey` | 22 chars base64url (128 bits) | the TV and the QR code           |

The QR code is `https://<worker>/#<tvId>.<sendKey>`. The key travels in the fragment, so it is
never sent in a page request; the page keeps it in `localStorage` and sends it with each call.

The first connection with a given `tvId` claims it: the object stores a SHA-256 of `secret` and
every later TV connection must match. The TV registers the current `sendKey` (hashed) on each
connection; "New link" on the TV makes a new one, which closes every phone that had the old one.

## HTTP

All under `/api/tv/<tvId>/`. Errors are `{"error": "..."}` with the status below.

| call                    | auth                       | what                                                                                       |
|-------------------------|----------------------------|--------------------------------------------------------------------------------------------|
| `GET  socket`           | `Authorization: Bearer <secret>`, `X-Send-Key`, `X-TV-Name` (URL-encoded, optional) | the TV's WebSocket. 401 on a wrong secret. |
| `GET  live?k=<sendKey>` | key in the query           | the phone's WebSocket. A wrong key is accepted and closed at once with 4401.                |
| `GET  status`           | `Authorization: Bearer <sendKey>` | `{online, name, uploads}`, the same as the phone socket's hello                     |
| `POST wallpapers`       | `Authorization: Bearer <sendKey>` | body: the image (JPEG, PNG or WebP, ≤ 20 MB). Headers: `X-Mode: test` for a look on the TV that is not saved (default `keep`, a wallpaper); `X-Draft` (8-32 `[a-z0-9]`, one per photo chosen on the phone: a keep replaces the TV's earlier keep of the same draft, and a test supersedes an earlier test still waiting); `X-From` (URL-encoded name of whoever sends it; the page asks for it once); `X-Meta` (URL-encoded JSON of what the phone read from the photo before rendering it: `takenAt` "YYYY-MM-DDTHH:MM[:SS][±HH:MM]", `lat`/`lon` in degrees, `camera`; the relay keeps only those, well-formed, and passes them on in the offer as `meta`); `X-Show: later` to only add a keep to the rotation. 201 `{id, state, online}`; 401 key reset, 404 no TV ever connected with that id, 409 a test while the TV is off (nothing stored), 413, 415, 429 (30 photos already waiting) |
| `GET  place?lat=&lon=` or `place?q=` | TV secret or send key | where that is (Google Geocoding): `{name, locality, region, country, countryCode, lat, lon}`; `q` is a name, such as a wallpaper's file name in words. 404 nowhere, 503 no maps key on the relay |
| `GET  map?lat=&lon=&zoom=4[&size=small]` | TV secret or send key | a 1280x720 PNG, a dark map centred there with country borders and names only (Google Maps Static); `size=small` is a 640x360 thumbnail without names. The TV draws its own pin at the centre |
| `POST wallpapers/<id>/keep` | `Authorization: Bearer <sendKey>` | Use this, for a test that is on the TV: the TV saves the copy it already has. 202, then the photo goes to `kept` (or `failed`: the TV no longer has it, so send a keep). 409 if it is not a test, not on the TV yet, or the TV is off |
| `GET  wallpapers/<id>`  | `Authorization: Bearer <secret>` | the TV fetches the bytes; 410 once they are gone                                     |

## WebSocket messages

JSON text, one object per message, field `t` is the kind. Both kinds of client send the plain text
`ping` every 25 s; the object answers `pong` without waking up (hibernation auto-response).

That pong comes from the runtime, so it also comes on a socket that outlived a deploy and is no
longer attached to the object (offers never reach it). The TV therefore also sends `{"t":"check"}`
every 2 minutes, which the object's own code answers with `{"t":"ok"}`; no answer within 20 s and
the TV reconnects.

Object → TV:

- `{"t":"hello","v"}` on connect (`v`: protocol version, 2), then a `wallpaper` for every photo still waiting.
- `{"t":"replaced"}` just before a 4000 close (a newer connection with the same secret).
- `{"t":"wallpaper","id","type","bytes","from","at","show","mode","draft","meta"}`: fetch it from
  `wallpapers/<id>`. `mode: "test"`: put it up as a preview, not saved, rotation held. `mode: "keep"`:
  save it (replacing the earlier keep of the same `draft`) and put it up, or with `show: false` only
  add it to the rotation (a backlog photo, or `X-Show: later`).
- `{"t":"keep","id","draft"}`: Use this for the test `id`: save the preview as a wallpaper.
- `{"t":"ok"}`: the answer to a `check`.

TV → object:

- `{"t":"got","id"}`: saved on the TV; the object deletes the bytes.
- `{"t":"shown","id","visible"}`: the crossfade started (`visible: false`: the TV is in another
  app and it shows when the home screen is back).
- `{"t":"kept","id"}`: the test is saved as a wallpaper.
- `{"t":"check"}`: is this socket still attached to the object? (answered with `ok`)
- `{"t":"failed","id","error"}`: the TV could not save or decode it (or, after a keep, no longer had it).
- `{"t":"key","sendKey"}`: "New link" on the TV.

Object → phone:

- `{"t":"hello","online","name","uploads":[{"id","at","from","state","visible"}]}` on connect.
- `{"t":"presence","online"}` when the TV connects or drops.
- `{"t":"upload","id","state","visible","mode","draft"}` as a photo moves `queued` → `delivered`
  → `shown` (→ `kept` for a test after Use this), or `failed`. For a keep, `delivered` means saved
  on the TV; for a test only that the TV has it.
- `{"t":"reset"}` just before a 4001 close: "New link" on the TV.

The messages before a close matter: when the object closes a hibernated socket, the client may only
report the close event seconds later (about 10 s under `wrangler dev`), while the message arrives at
once.

Close codes: 4000 a newer TV connection replaced this one, 4001 the link was reset on the TV
(phone), 4401 wrong key (phone), 4404 no TV with that id (phone).

Place names and maps come from Google Maps Platform through the relay, so the key never reaches a
phone or the TV. Each TV's object caches them in its SQLite storage for 30 days, which Google's terms
allow; the edge cache does not work on workers.dev.

## Develop and deploy

```sh
cd cloud
wrangler dev                 # http://localhost:8787, the emulator reaches it at http://10.0.2.2:8787
node test/flow.mjs           # end-to-end: a fake TV and a fake phone against wrangler dev
node test/fake-tv.mjs        # a stand-in TV for working on the page; prints the page address
wrangler deploy              # https://liquid.<account>.workers.dev
wrangler secret put GOOGLE_MAPS_KEY   # Maps Static API + Geocoding API; locally: GOOGLE_MAPS_KEY=… in .dev.vars
```

The launcher's server address is `phone.server` in `launcher.json`; when it is absent the build's
default (`PHONE_SERVER` in `app/build.gradle.kts`) is used.
