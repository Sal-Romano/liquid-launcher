// Liquid: frame a photo for the TV and send it. No build step, no dependencies.
// Protocol: ../README.md. The pairing comes from the QR code on the TV: #<tvId>.<sendKey>.
"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const app = $("app");
  const screen = $("screen");
  const cv = $("preview");
  const ctx = cv.getContext("2d");

  // The framing is reckoned on the TV's 1920 x 1080 layout; photos go out at up to 3840 x 2160,
  // the panel's own resolution (see outputSize).
  const TV_W = 1920;
  const TV_H = 1080;

  const finePointer = !!(window.matchMedia && window.matchMedia("(hover: hover) and (pointer: fine)").matches);

  const TV_ID = /^[a-z2-7]{16}$/;
  const KEY = /^[A-Za-z0-9_-]{16,128}$/;

  const PAIR_COPY = {
    none: ["Scan the code on your TV", "On the TV, open Settings, then Wallpapers. Point your phone’s camera at the code there."],
    reset: ["This link was reset on the TV", "Scan the new code: on the TV, open Settings, then Wallpapers."],
    unknown: ["No TV with that code", "Scan it again: on the TV, open Settings, then Wallpapers."],
    garbled: ["That code didn’t come through", "Scan it again: on the TV, open Settings, then Wallpapers."],
  };

  // ---------------------------------------------------------------- storage

  const store = {
    get(k) {
      try {
        const v = localStorage.getItem(k);
        return v === null ? null : JSON.parse(v);
      } catch {
        return null;
      }
    },
    set(k, v) {
      try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* private mode: live without it */ }
    },
    del(k) {
      try { localStorage.removeItem(k); } catch { /* as above */ }
    },
  };

  // ---------------------------------------------------------------- pairing

  let pair = null;

  /** The TV from the URL's fragment, else the one remembered; puts a remembered one back in the URL. */
  function readPairing() {
    const hash = location.hash.slice(1);
    const dot = hash.indexOf(".");
    if (dot > 0) {
      const id = hash.slice(0, dot);
      const key = hash.slice(dot + 1);
      if (TV_ID.test(id) && KEY.test(key)) {
        const p = { id, key };
        store.set("liquid.tv", p);
        return { pair: p };
      }
    }
    const saved = store.get("liquid.tv");
    if (saved && TV_ID.test(saved.id) && KEY.test(saved.key)) {
      // Back in the address so "Add to Home Screen" keeps the TV.
      history.replaceState(null, "", `${location.pathname}${location.search}#${saved.id}.${saved.key}`);
      return { pair: saved };
    }
    return { pair: null, garbled: hash.length > 0 };
  }

  function unpair(why) {
    pair = null;
    store.del("liquid.tv");
    closeSocket();
    history.replaceState(null, "", `${location.pathname}${location.search}`);
    busy = null;
    clearTimeout(keepTimer);
    clearTimeout(saveTimer);
    note("");
    showPair(why);
  }

  function start() {
    const r = readPairing();
    if (!r.pair) {
      pair = null;
      closeSocket();
      showPair(r.garbled ? "garbled" : "none");
      return;
    }
    const changed = !pair || pair.id !== r.pair.id || pair.key !== r.pair.key;
    pair = r.pair;
    showCompose();
    if (changed) {
      closeSocket();
      tv = { online: null, name: null };
      linked = false;
      uploads.clear();
      renderSent();
      retries = 0;
      connect();
    }
  }

  function showPair(why) {
    app.dataset.view = "pair";
    $("pair").hidden = false;
    $("compose").hidden = true;
    $("bar").hidden = true;
    $("status").hidden = true;
    const [title, text] = PAIR_COPY[why] || PAIR_COPY.none;
    $("pair-title").textContent = title;
    $("pair-text").textContent = text;
  }

  function showCompose() {
    app.dataset.view = "compose";
    $("pair").hidden = true;
    $("compose").hidden = false;
    $("bar").hidden = false;
    $("status").hidden = false;
    renderStatus();
    sizeScreen();
  }

  // ---------------------------------------------------------------- the live link

  let ws = null;
  let linked = false; // the socket is open and has said hello
  let retries = 0;
  let retryTimer = 0;
  let pingTimer = 0;
  let lastHeard = 0;
  let tv = { online: null, name: null };

  function connect() {
    clearTimeout(retryTimer);
    if (!pair || ws) return;
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    let sock;
    try {
      sock = new WebSocket(`${scheme}://${location.host}/api/tv/${pair.id}/live?k=${encodeURIComponent(pair.key)}`);
    } catch {
      retryLater();
      return;
    }
    ws = sock;
    renderStatus();
    sock.onopen = () => {
      lastHeard = Date.now();
      clearInterval(pingTimer);
      pingTimer = setInterval(() => {
        if (ws !== sock) return;
        // Nothing back for a minute and a half: the socket is dead without knowing it.
        if (Date.now() - lastHeard > 90_000) return abandon(sock);
        try { sock.send("ping"); } catch { /* closing */ }
      }, 25_000);
    };
    sock.onmessage = (e) => {
      if (ws !== sock || typeof e.data !== "string") return;
      lastHeard = Date.now();
      if (e.data === "pong") return;
      let m;
      try { m = JSON.parse(e.data); } catch { return; }
      onMessage(m);
    };
    sock.onclose = (e) => {
      if (ws !== sock) return;
      ws = null;
      linked = false;
      clearInterval(pingTimer);
      if (e.code === 4001 || e.code === 4401) return unpair("reset");
      if (e.code === 4404) return unpair("unknown");
      retryLater();
    };
  }

  /** Drop a socket that has gone quiet and start a fresh one at once. */
  function abandon(sock) {
    if (ws !== sock) return;
    sock.onclose = sock.onmessage = sock.onopen = null;
    try { sock.close(); } catch { /* already gone */ }
    ws = null;
    linked = false;
    clearInterval(pingTimer);
    retries = 0;
    connect();
  }

  function retryLater() {
    clearTimeout(retryTimer);
    renderStatus();
    if (!pair) return;
    const delay = Math.min(30_000, 1000 * 2 ** retries++);
    retryTimer = setTimeout(connect, delay);
  }

  function closeSocket() {
    clearTimeout(retryTimer);
    clearInterval(pingTimer);
    const sock = ws;
    ws = null;
    linked = false;
    if (sock) {
      sock.onclose = sock.onmessage = sock.onopen = null;
      try { sock.close(1000); } catch { /* already gone */ }
    }
  }

  function onMessage(m) {
    switch (m.t) {
      case "hello":
        retries = 0;
        linked = true;
        tv = { online: !!m.online, name: typeof m.name === "string" && m.name.trim() ? m.name.trim() : null };
        if (Array.isArray(m.uploads)) m.uploads.forEach(merge);
        renderStatus();
        renderSent();
        follow();
        renderNote();
        break;
      case "presence":
        tv.online = !!m.online;
        renderStatus();
        renderSent();
        renderButtons();
        renderNote();
        break;
      case "upload":
        merge(m);
        renderSent();
        follow();
        break;
      case "reset":
        // Said just before the close, which can take a while to arrive.
        unpair("reset");
        break;
    }
  }

  function renderStatus() {
    const pill = $("status");
    let kind;
    let text;
    if (!linked) {
      kind = "connecting";
      text = retries > 1 ? "Reconnecting…" : "Connecting…";
    } else if (tv.online) {
      kind = "online";
      text = tv.name || "Your TV";
    } else {
      kind = "offline";
      text = "TV is off — photos wait for it";
    }
    pill.dataset.kind = kind;
    $("status-text").textContent = text;
  }

  // ---------------------------------------------------------------- what was sent

  /** id → { id, at, from, state, visible, mode, draft } as the relay reports them. */
  const uploads = new Map();
  /** Thumbnails of what this phone sent, by draft. */
  const thumbs = new Map();
  const RANK = { queued: 0, delivered: 1, shown: 2, kept: 3, failed: 4 };

  function merge(u) {
    if (!u || typeof u.id !== "string" || !(u.state in RANK)) return;
    const old = uploads.get(u.id) || {};
    const next = { ...old, id: u.id };
    if (typeof u.at === "number") next.at = u.at;
    if (typeof u.from === "string" || u.from === null) next.from = u.from ?? old.from ?? null;
    if (u.mode === "test" || u.mode === "keep") next.mode = u.mode;
    if (typeof u.draft === "string") next.draft = u.draft;
    // States only move forward; a late "queued" never undoes "shown".
    next.state = old.state && RANK[old.state] > RANK[u.state] ? old.state : u.state;
    if (typeof u.visible === "boolean") next.visible = u.visible;
    uploads.set(u.id, next);
  }

  /** One row per photo (draft): Saved once any version of it was saved, else Test. */
  function renderSent() {
    const groups = new Map();
    for (const u of uploads.values()) {
      if (!u.at) continue;
      const k = u.draft || u.id;
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(u);
    }
    const rows = [...groups].map(([k, list]) => summarize(k, list)).sort((a, b) => b.at - a.at).slice(0, 5);
    $("sent").hidden = rows.length === 0;
    $("sent-list").replaceChildren(...rows.map(sentRow));
  }

  function summarize(k, list) {
    list.sort((a, b) => b.at - a.at);
    const latest = list[0];
    const saved = list.find((u) => (u.mode !== "test" && u.state !== "failed") || u.state === "kept");
    let chip;
    let tone;
    if (saved) {
      if (saved.state === "queued") {
        chip = linked && !tv.online ? "Waiting for TV" : "Saving";
        tone = "wait";
      } else {
        chip = "Saved";
        tone = "saved";
      }
    } else if (latest.mode === "test") {
      chip = "Test";
      tone = latest.state === "failed" ? "old" : "test";
    } else {
      chip = "Didn’t arrive";
      tone = "failed";
    }
    return { at: latest.at, from: latest.from, chip, tone, thumb: thumbs.get(k) || null };
  }

  const GLYPH = '<svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="4" width="15" height="12" rx="2.6" fill="none" stroke="currentColor" stroke-width="1.6"/><circle cx="7.4" cy="8.4" r="1.5" fill="currentColor"/><path d="M3.5 14.5l4.5-4 3 2.6 2.5-2 3 2.6" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>';

  function sentRow(row) {
    const li = el("li");
    const thumb = el("span", "thumb");
    if (row.thumb) thumb.style.backgroundImage = `url("${row.thumb}")`;
    else thumb.innerHTML = GLYPH;
    const what = el("div", "what");
    what.append(el("div", "when", when(row.at)), el("div", "who", row.from ? `From ${row.from}` : "From a phone"));
    const state = el("span", "state", row.chip);
    state.dataset.tone = row.tone;
    li.append(thumb, what, state);
    return li;
  }

  function when(at) {
    const d = new Date(at);
    const now = new Date();
    const time = d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    const day = (x) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
    const days = Math.round((day(now) - day(d)) / 86_400_000);
    if (days === 0) return time;
    if (days === 1) return `Yesterday, ${time}`;
    return `${d.toLocaleDateString([], { month: "short", day: "numeric" })}, ${time}`;
  }

  // ---------------------------------------------------------------- the photo

  /** { img, url, w, h, preview: { source, scale }, blur: canvas } */
  let photo = null;
  /** The framing: mode, zoom over the mode's base scale, and the photo point at the TV's centre. */
  const view = { mode: "fill", zoom: 1, cx: 0, cy: 0 };
  /** What is drawn now ({ s: TV px per photo px, cx, cy }); eases to the framing on Fill/Fit and reset. */
  const drawn = { s: 1, cx: 0, cy: 0 };
  let tween = null;

  function choose() {
    const input = $("file");
    input.value = "";
    input.click();
  }

  async function load(file) {
    if (file.type && !file.type.startsWith("image/")) {
      note("That isn’t a photo.", "bad");
      return;
    }
    note("Opening…");
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.decoding = "async";
    try {
      await new Promise((resolve, reject) => {
        img.onload = resolve;
        img.onerror = reject;
        img.src = url;
      });
      if (!img.naturalWidth || !img.naturalHeight) throw new Error("empty");
    } catch {
      URL.revokeObjectURL(url);
      note("Couldn’t open that photo. Try a JPEG or PNG.", "bad");
      return;
    }
    if (photo) {
      URL.revokeObjectURL(photo.url);
      // Safari counts canvas memory until a canvas is sized to zero; a few photos in, it refuses more.
      const old = photo.preview.source;
      if (old instanceof HTMLCanvasElement) { old.width = 0; old.height = 0; }
    }
    // naturalWidth/Height already follow the EXIF orientation.
    const w = img.naturalWidth;
    const h = img.naturalHeight;
    photo = { img, url, w, h };
    // A copy small enough to pan and pinch smoothly; the full photo is used for the send.
    photo.preview = downscale(img, w, h, 2400);
    photo.blur = blurCover(photo.preview.source, w * photo.preview.scale, h * photo.preview.scale);
    setBackdrop(photo.blur);

    view.zoom = 1;
    view.cx = w / 2;
    view.cy = h / 2;
    clampView();
    tween = null;
    Object.assign(drawn, target());
    app.dataset.photo = "yes";
    $("tools").hidden = false;
    $("hint").hidden = false;
    // A new photo is a new draft: its saves never replace another photo's.
    screen.classList.remove("shown");
    draft = newDraft();
    test = null;
    save = null;
    everSaved = false;
    onTv = null;
    clearTimeout(keepTimer);
    clearTimeout(saveTimer);
    note("");
    renderButtons();
    sizeScreen();
    requestDraw();
  }

  /** Scale for the mode: Fill covers the TV (cropping), Fit shows all of the photo. */
  function base(mode = view.mode) {
    const fill = Math.max(TV_W / photo.w, TV_H / photo.h);
    const fit = Math.min(TV_W / photo.w, TV_H / photo.h);
    return mode === "fill" ? fill : fit;
  }

  /** Up to about twice the photo's own resolution, never below the mode's base. */
  function maxZoom() {
    return Math.max(1, Math.min(8, 2.2 / base()));
  }

  function clampView() {
    view.zoom = Math.min(maxZoom(), Math.max(1, view.zoom));
    const s = base() * view.zoom;
    const hw = TV_W / 2 / s;
    const hh = TV_H / 2 / s;
    // An axis the photo does not fill stays centred; one it overfills cannot show an edge.
    view.cx = 2 * hw >= photo.w ? photo.w / 2 : Math.min(photo.w - hw, Math.max(hw, view.cx));
    view.cy = 2 * hh >= photo.h ? photo.h / 2 : Math.min(photo.h - hh, Math.max(hh, view.cy));
  }

  function target() {
    return { s: base() * view.zoom, cx: view.cx, cy: view.cy };
  }

  function animateTo() {
    if (reducedMotion()) {
      tween = null;
      Object.assign(drawn, target());
    } else {
      tween = { from: { ...drawn }, start: performance.now(), dur: 320 };
    }
    requestDraw();
  }

  function reducedMotion() {
    return window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }

  // ---------------------------------------------------------------- drawing

  let drawQueued = false;

  function requestDraw() {
    if (drawQueued) return;
    drawQueued = true;
    requestAnimationFrame(frame);
  }

  function frame(now) {
    drawQueued = false;
    if (!photo || !cv.width) return;
    const to = target();
    if (tween) {
      const p = Math.min(1, (now - tween.start) / tween.dur);
      const e = 1 - Math.pow(1 - p, 3);
      drawn.s = tween.from.s + (to.s - tween.from.s) * e;
      drawn.cx = tween.from.cx + (to.cx - tween.from.cx) * e;
      drawn.cy = tween.from.cy + (to.cy - tween.from.cy) * e;
      if (p >= 1) tween = null;
      else requestDraw();
    } else {
      Object.assign(drawn, to);
    }
    paint(ctx, cv.width, cv.height, photo.preview.source, drawn);
    updateMeter();
    renderButtons();
  }

  /**
   * Draws the photo as the TV will show it into a W x H canvas (any size with the TV's shape):
   * the blurred copy first wherever the photo does not cover, then the photo itself.
   */
  function paint(c, W, H, source, st) {
    const k = W / TV_W;
    const sk = st.s * k;
    const dw = photo.w * sk;
    const dh = photo.h * sk;
    const dx = W / 2 - st.cx * sk;
    const dy = H / 2 - st.cy * sk;
    c.imageSmoothingEnabled = true;
    c.imageSmoothingQuality = "high";
    const covers = dx <= 0.5 && dy <= 0.5 && dx + dw >= W - 0.5 && dy + dh >= H - 0.5;
    if (!covers) {
      c.drawImage(photo.blur, 0, 0, W, H);
      c.fillStyle = "rgba(0, 0, 0, 0.24)";
      c.fillRect(0, 0, W, H);
    }
    c.drawImage(source, dx, dy, dw, dh);
  }

  function sizeScreen() {
    const r = screen.getBoundingClientRect();
    if (!r.width) return;
    const dpr = Math.min(3, window.devicePixelRatio || 1);
    const w = Math.round(r.width * dpr);
    const h = Math.round(r.height * dpr);
    if (cv.width !== w || cv.height !== h) {
      cv.width = w;
      cv.height = h;
    }
    // One TV dp (the TV's layout is 960 x 540 dp) in CSS px, for the clock and dock overlay.
    screen.style.setProperty("--u", `${r.width / 960}px`);
    requestDraw();
  }

  // ---------------------------------------------------------------- image work

  function canvas(w, h) {
    const c = document.createElement("canvas");
    c.width = w;
    c.height = h;
    return c;
  }

  /**
   * [src] (w x h) scaled so its longer side is at most [maxSide], halving step by step first so
   * a big reduction stays sharp. Returns the source to draw and its scale relative to the photo.
   */
  function downscale(src, w, h, maxSide) {
    const k = Math.min(1, maxSide / Math.max(w, h));
    if (k >= 1) return { source: src, scale: 1 };
    const tw = Math.max(1, Math.round(w * k));
    const th = Math.max(1, Math.round(h * k));
    let cur = src;
    let cw = w;
    let ch = h;
    const steps = [];
    while (cw / 2 >= tw) {
      const c = canvas(Math.round(cw / 2), Math.round(ch / 2));
      drawSmooth(c, cur);
      steps.push(c);
      cur = c;
      cw = c.width;
      ch = c.height;
    }
    let out = cur;
    if (cw !== tw || ch !== th) {
      out = canvas(tw, th);
      drawSmooth(out, cur);
    }
    // Safari keeps canvas memory until the size goes to zero.
    for (const c of steps) if (c !== out) { c.width = 0; c.height = 0; }
    return { source: out, scale: tw / w };
  }

  function drawSmooth(dst, src) {
    const c = dst.getContext("2d");
    c.imageSmoothingEnabled = true;
    c.imageSmoothingQuality = "high";
    c.drawImage(src, 0, 0, dst.width, dst.height);
  }

  /** The photo covering a 192 x 108 canvas, deeply blurred: what Fit shows around the photo. */
  function blurCover(src, w, h) {
    const BW = 192;
    const BH = 108;
    const c = canvas(BW, BH);
    const g = c.getContext("2d", { willReadFrequently: true });
    const s = Math.max(BW / w, BH / h);
    g.imageSmoothingEnabled = true;
    g.imageSmoothingQuality = "high";
    g.drawImage(src, (BW - w * s) / 2, (BH - h * s) / 2, w * s, h * s);
    const data = g.getImageData(0, 0, BW, BH);
    boxBlur(data.data, BW, BH, 7, 3);
    g.putImageData(data, 0, 0);
    return c;
  }

  // A box blur run three times approximates a Gaussian; the same as the launcher's Blur.kt.
  function boxBlur(px, w, h, r, passes) {
    const tmp = new Uint8ClampedArray(px.length);
    for (let i = 0; i < passes; i++) {
      blurH(px, tmp, w, h, r);
      blurV(tmp, px, w, h, r);
    }
  }

  function blurH(src, dst, w, h, r) {
    const div = 2 * r + 1;
    for (let y = 0; y < h; y++) {
      const row = y * w * 4;
      let R = 0, G = 0, B = 0, A = 0;
      for (let i = -r; i <= r; i++) {
        const o = row + Math.min(w - 1, Math.max(0, i)) * 4;
        R += src[o]; G += src[o + 1]; B += src[o + 2]; A += src[o + 3];
      }
      for (let x = 0; x < w; x++) {
        const o = row + x * 4;
        dst[o] = R / div; dst[o + 1] = G / div; dst[o + 2] = B / div; dst[o + 3] = A / div;
        const out = row + Math.max(0, x - r) * 4;
        const inn = row + Math.min(w - 1, x + r + 1) * 4;
        R += src[inn] - src[out]; G += src[inn + 1] - src[out + 1]; B += src[inn + 2] - src[out + 2]; A += src[inn + 3] - src[out + 3];
      }
    }
  }

  function blurV(src, dst, w, h, r) {
    const div = 2 * r + 1;
    const stride = w * 4;
    for (let x = 0; x < w; x++) {
      const col = x * 4;
      let R = 0, G = 0, B = 0, A = 0;
      for (let i = -r; i <= r; i++) {
        const o = Math.min(h - 1, Math.max(0, i)) * stride + col;
        R += src[o]; G += src[o + 1]; B += src[o + 2]; A += src[o + 3];
      }
      for (let y = 0; y < h; y++) {
        const o = y * stride + col;
        dst[o] = R / div; dst[o + 1] = G / div; dst[o + 2] = B / div; dst[o + 3] = A / div;
        const out = Math.max(0, y - r) * stride + col;
        const inn = Math.min(h - 1, y + r + 1) * stride + col;
        R += src[inn] - src[out]; G += src[inn + 1] - src[out + 1]; B += src[inn + 2] - src[out + 2]; A += src[inn + 3] - src[out + 3];
      }
    }
  }

  /**
   * The size to send: the panel's 3840 x 2160 when the framing holds at least that many of the
   * photo's pixels across, else as many as it holds (16:9, a multiple of 16), never under 1920 x 1080.
   */
  function outputSize() {
    const across = TV_W / (base() * view.zoom); // photo px across the frame (Fit's surround included)
    const w = Math.round(Math.min(3840, Math.max(1920, across)) / 16) * 16;
    return { w, h: (w / 16) * 9 };
  }

  /** The framed photo, rendered from the full-resolution original. */
  function renderJpeg() {
    const { w: W, h: H } = outputSize();
    const out = canvas(W, H);
    const st = target();
    // Output px per photo px. Draw from a copy at most 1.5x the size needed, so the last step is gentle.
    const k = st.s * (W / TV_W);
    const src = k < 0.66 ? downscale(photo.img, photo.w, photo.h, Math.ceil(Math.max(photo.w, photo.h) * k * 1.5)).source : photo.img;
    paint(out.getContext("2d"), W, H, src, st);
    if (src !== photo.img) { src.width = 0; src.height = 0; }
    return toBlob(out, 0.9).finally(() => { out.width = 0; out.height = 0; });
  }

  function renderThumb() {
    const c = canvas(176, 99);
    paint(c.getContext("2d"), c.width, c.height, photo.preview.source, target());
    return toBlob(c, 0.8).then((b) => URL.createObjectURL(b));
  }

  function toBlob(c, quality) {
    return new Promise((resolve, reject) => c.toBlob((b) => (b ? resolve(b) : reject(new Error("toBlob"))), "image/jpeg", quality));
  }

  // The page's own background: the photo, blurred, behind the glass.
  let backdropUrl = null;
  let backdropFlip = 0;

  function setBackdrop(blur) {
    toBlob(blur, 0.85).then((b) => {
      const url = URL.createObjectURL(b);
      const layers = document.querySelectorAll("#backdrop .layer");
      backdropFlip ^= 1;
      const next = layers[backdropFlip];
      const prev = layers[backdropFlip ^ 1];
      next.style.backgroundImage = `url("${url}")`;
      next.classList.add("on");
      prev.classList.remove("on");
      const old = backdropUrl;
      backdropUrl = url;
      if (old) setTimeout(() => URL.revokeObjectURL(old), 1500);
    }, () => {});
  }

  // ---------------------------------------------------------------- framing by hand

  const pointers = new Map();
  let gesture = null;
  let moved = false;
  let lastTap = { t: 0, x: 0, y: 0 };

  function beginGesture() {
    const pts = [...pointers.values()];
    if (!photo || pts.length === 0) {
      gesture = null;
      return;
    }
    const r = screen.getBoundingClientRect();
    const f = TV_W / r.width; // TV px per CSS px
    const s = base() * view.zoom;
    if (pts.length === 1) {
      gesture = { kind: "pan", x: pts[0].x, y: pts[0].y, cx: view.cx, cy: view.cy, s, f };
    } else {
      const [a, b] = pts;
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      const centre = { x: r.left + r.width / 2, y: r.top + r.height / 2 };
      gesture = {
        kind: "pinch",
        d: Math.hypot(a.x - b.x, a.y - b.y) || 1,
        zoom: view.zoom,
        // The photo point under the fingers stays under the fingers.
        px: view.cx + ((mid.x - centre.x) * f) / s,
        py: view.cy + ((mid.y - centre.y) * f) / s,
        centre,
        f,
      };
    }
  }

  function moveGesture() {
    if (!gesture) return;
    const pts = [...pointers.values()];
    if (gesture.kind === "pan" && pts.length === 1) {
      const dx = pts[0].x - gesture.x;
      const dy = pts[0].y - gesture.y;
      if (Math.abs(dx) + Math.abs(dy) > 5) moved = true;
      if (!moved) return;
      view.cx = gesture.cx - (dx * gesture.f) / gesture.s;
      view.cy = gesture.cy - (dy * gesture.f) / gesture.s;
    } else if (gesture.kind === "pinch" && pts.length >= 2) {
      moved = true;
      const [a, b] = pts;
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      view.zoom = gesture.zoom * ((Math.hypot(a.x - b.x, a.y - b.y) || 1) / gesture.d);
      view.zoom = Math.min(maxZoom(), Math.max(1, view.zoom));
      const s = base() * view.zoom;
      view.cx = gesture.px - ((mid.x - gesture.centre.x) * gesture.f) / s;
      view.cy = gesture.py - ((mid.y - gesture.centre.y) * gesture.f) / s;
    } else {
      return;
    }
    clampView();
    tween = null;
    requestDraw();
  }

  cv.addEventListener("pointerdown", (e) => {
    if (!photo) return;
    e.preventDefault();
    try { cv.setPointerCapture(e.pointerId); } catch { /* fine */ }
    if (pointers.size === 0) moved = false;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    beginGesture();
  });

  cv.addEventListener("pointermove", (e) => {
    if (!pointers.has(e.pointerId)) return;
    pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
    moveGesture();
  });

  function endPointer(e) {
    const p = pointers.get(e.pointerId);
    if (!p) return;
    pointers.delete(e.pointerId);
    if (pointers.size === 0 && !moved && e.type === "pointerup") tap(p);
    beginGesture();
  }
  cv.addEventListener("pointerup", endPointer);
  cv.addEventListener("pointercancel", endPointer);
  cv.addEventListener("lostpointercapture", endPointer);

  function tap(p) {
    const now = performance.now();
    if (now - lastTap.t < 330 && Math.hypot(p.x - lastTap.x, p.y - lastTap.y) < 32) {
      lastTap.t = 0;
      view.zoom = 1;
      view.cx = photo.w / 2;
      view.cy = photo.h / 2;
      clampView();
      animateTo();
    } else {
      lastTap = { t: now, x: p.x, y: p.y };
    }
  }

  // A mouse wheel or trackpad zooms about the pointer.
  cv.addEventListener("wheel", (e) => {
    if (!photo) return;
    e.preventDefault();
    const r = screen.getBoundingClientRect();
    const f = TV_W / r.width;
    const ox = e.clientX - (r.left + r.width / 2);
    const oy = e.clientY - (r.top + r.height / 2);
    const s0 = base() * view.zoom;
    const px = view.cx + (ox * f) / s0;
    const py = view.cy + (oy * f) / s0;
    // A trackpad pinch arrives as a wheel with ctrlKey and small deltas.
    const rate = e.ctrlKey ? 0.012 : 0.0016;
    view.zoom = Math.min(maxZoom(), Math.max(1, view.zoom * Math.exp(-e.deltaY * rate)));
    const s1 = base() * view.zoom;
    view.cx = px - (ox * f) / s1;
    view.cy = py - (oy * f) / s1;
    clampView();
    tween = null;
    requestDraw();
  }, { passive: false });

  $("mode").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-mode]");
    if (!b || !photo || view.mode === b.dataset.mode) return;
    view.mode = b.dataset.mode;
    view.zoom = 1;
    clampView();
    animateTo();
    for (const x of $("mode").querySelectorAll("button")) x.setAttribute("aria-checked", String(x === b));
  });

  // The TV's clock and dock over the preview.
  function setChrome(on) {
    $("chrome-toggle").setAttribute("aria-pressed", String(on));
    $("chrome").classList.toggle("off", !on);
  }
  $("chrome-toggle").addEventListener("click", () => {
    const on = $("chrome-toggle").getAttribute("aria-pressed") !== "true";
    setChrome(on);
    store.set("liquid.chrome", on);
  });

  function tickClock() {
    const now = new Date();
    let cycle = "h12";
    try { cycle = new Intl.DateTimeFormat([], { hour: "numeric" }).resolvedOptions().hourCycle || "h12"; } catch { /* default */ }
    const h = now.getHours();
    const hour = cycle === "h23" || cycle === "h24" ? h : h % 12 || 12;
    $("tv-time").textContent = `${hour}:${String(now.getMinutes()).padStart(2, "0")}`;
    $("tv-date").textContent = now.toLocaleDateString([], { weekday: "long", month: "long", day: "numeric" });
    setTimeout(tickClock, 60_000 - (Date.now() % 60_000) + 50);
  }

  // ---------------------------------------------------------------- how sharp on the TV

  // r = photo pixels per pixel of a 3840-wide TV. In Fit the surround is a blur and does not count:
  // r comes from the photo's own size on screen, which is the same formula.
  const LEVELS = [
    // The TV composes its home screen at 1920x1080 (the panel scales it up), so half a 4K
    // frame's worth of photo, one photo pixel per screen pixel, is as sharp as it can show.
    { min: 0.5, level: "sharp", text: "Sharp" },
    { min: 0.33, level: "soft", text: "A little soft" },
    { min: 0, level: "blurry", text: "Blurry on the TV" },
  ];
  let meterShown = "";
  let bannerShown = "";

  function updateMeter() {
    if (!photo) return;
    const r = 1 / (2 * base() * view.zoom);
    const L = LEVELS.find((x) => r >= x.min);
    if (L.level !== meterShown) {
      meterShown = L.level;
      $("meter").dataset.level = L.level;
      $("meter-text").textContent = L.text;
    }
    // Soft or worse: say why, and what helps. A small photo is soft at any zoom.
    let banner = "";
    if (L.level === "soft" || L.level === "blurry") {
      const unzoomed = 1 / (2 * base());
      banner = unzoomed < 0.5 ? "small" : "zoom";
    }
    const key = banner && `${banner}:${L.level}:${photo.w}x${photo.h}`;
    if (key === bannerShown) return;
    bannerShown = key;
    const box = $("soft");
    box.hidden = !banner;
    if (!banner) return;
    box.dataset.level = L.level;
    $("soft-text").textContent = banner === "small"
      ? `This photo is small (${photo.w}×${photo.h}): it’ll look soft on the TV.`
      : `Zoomed in past the photo’s detail: it’ll look soft on the TV. ${finePointer ? "Zoom" : "Pinch"} out a little.`;
  }

  // ---------------------------------------------------------------- test, then use

  /** One per photo chosen; a save replaces the TV's earlier save of the same draft. */
  let draft = null;
  /** The latest test of this draft: { id, sig, landed, failed }. */
  let test = null;
  /** The latest save of this draft: { id, sig, via: "test" | "keep", offline, done }. */
  let save = null;
  /** Some version of this draft is saved on the TV (or waiting for it): the button offers Update. */
  let everSaved = false;
  /** The framing of this draft that is up on the TV: { sig, at, test }. A test lasts 10 minutes there. */
  let onTv = null;
  /** An upload or Use this in flight: "test" | "use". One at a time. */
  let busy = null;
  let busyProgress = 0;
  let keepTimer = 0;
  let saveTimer = 0;
  let noteText = "";
  let noteTone = "";
  /** The framing a note is about ("This is a test…", "Saved…"); it goes when the framing moves on. */
  let noteSig = null;
  let paintedButtons = "";

  const TEST_LASTS = 10 * 60_000;

  function newDraft() {
    const b = crypto.getRandomValues(new Uint8Array(16));
    return [...b].map((x) => (x % 36).toString(36)).join("");
  }

  /** The framing as a string; the same framing gives the same string. */
  function framing() {
    return `${view.mode}:${view.zoom.toFixed(3)}:${Math.round(view.cx)}:${Math.round(view.cy)}`;
  }

  function note(text, tone, about = null) {
    noteText = text || "";
    noteTone = tone || "";
    noteSig = about;
    renderNote();
  }

  /** The last thing said, or with nothing to say and the TV off, why Test is greyed out. */
  function renderNote() {
    let text = noteText;
    let tone = noteTone;
    if (!text && photo && pair && linked && tv.online === false) {
      text = "Test needs the TV on.";
      tone = "warn";
    }
    const n = $("note");
    n.textContent = text;
    if (tone) n.dataset.tone = tone;
    else delete n.dataset.tone;
  }

  function renderButtons() {
    const has = !!photo;
    $("pick-main").hidden = has;
    $("actions").hidden = !has;
    if (!has) return;
    const sig = framing();
    if (noteSig && noteSig !== sig) note("");
    const off = linked && tv.online === false;
    const tu = test && uploads.get(test.id);
    const su = save && uploads.get(save.id);
    const upNow = onTv && onTv.sig === sig && (!onTv.test || Date.now() - onTv.at < TEST_LASTS);
    const pct = (verb) => (busyProgress > 0 ? `${verb} ${Math.round(busyProgress * 100)}%` : "Preparing…");

    let t = { state: "idle", text: test ? "Test again" : "Test on TV" };
    if (busy === "test") t = { state: "sending", text: pct("Sending"), p: busyProgress };
    else if (tu && test.sig === sig && (tu.state === "queued" || tu.state === "delivered")) t = { state: "waiting", text: "Sending…" };
    else if (off) t.disabled = true;
    else if (upNow) t = { state: "ontv", text: "On the TV" };

    let u = { state: "idle", text: everSaved ? "Update" : "Use this" };
    if (busy === "use") u = { state: "sending", text: pct("Saving"), p: busyProgress };
    else if (save && save.sig === sig && su && su.state !== "failed") {
      const saved = save.done || save.offline || ["delivered", "shown", "kept"].includes(su.state);
      u = saved ? { state: "done", text: "Saved" } : { state: "waiting", text: "Saving…" };
    }
    // One upload at a time.
    if (busy === "use") t.disabled = true;
    if (busy === "test") u.disabled = true;

    const key = JSON.stringify([t, u]);
    if (key === paintedButtons) return;
    paintedButtons = key;
    paintButton($("test"), $("test-text"), t);
    paintButton($("use"), $("use-text"), u);
  }

  function paintButton(b, label, st) {
    b.dataset.state = st.state;
    b.disabled = !!st.disabled;
    b.style.setProperty("--p", String(st.p || 0));
    b.setAttribute("aria-busy", String(st.state === "sending" || st.state === "waiting"));
    label.textContent = st.text;
  }

  $("pick-main").addEventListener("click", choose);

  $("test").addEventListener("click", () => {
    const st = $("test").dataset.state;
    if (!busy && st !== "sending" && st !== "waiting") sendTest();
  });

  $("use").addEventListener("click", () => {
    const st = $("use").dataset.state;
    if (!busy && st !== "sending" && st !== "waiting" && st !== "done") useThis();
  });

  /** Test on TV: up on the TV as a preview, not saved; a newer test replaces it. */
  async function sendTest() {
    if (!photo || !pair) return;
    const mine = draft;
    const sig = framing();
    busy = "test";
    busyProgress = 0;
    note("");
    renderButtons();
    const r = await sendPhoto("test", mine);
    busy = null;
    busyProgress = 0;
    if (r && r.status === 201) {
      merge({ id: r.body.id, at: uploads.get(r.body.id)?.at ?? Date.now(), from: r.from, state: "queued", mode: "test", draft: mine });
      if (draft === mine) test = { id: r.body.id, sig };
      follow();
    } else if (r && r.status === 409) {
      note("The TV is off. Test needs the TV on.", "warn");
    } else if (r) {
      trouble(r.status);
    }
    renderSent();
    renderButtons();
  }

  /**
   * Use this. The test on the TV, if it is this framing: the TV saves the copy it has (nothing is
   * sent again). Otherwise the framing goes up as a keep of this draft, replacing its earlier save.
   */
  async function useThis() {
    if (!photo || !pair) return;
    const sig = framing();
    const tu = test && uploads.get(test.id);
    if (tu && test.sig === sig && tu.state === "shown" && tv.online !== false) {
      const id = test.id;
      busy = "use";
      busyProgress = 0;
      note("");
      renderButtons();
      const r = await call(`wallpapers/${id}/keep`);
      busy = null;
      if (r.status === 202 || r.status === 200) {
        save = { id, sig, via: "test" };
        clearTimeout(keepTimer);
        // No word from the TV: send the photo itself.
        keepTimer = setTimeout(() => {
          if (save && save.id === id && !save.done) keepUpload(save.sig);
        }, 10_000);
        follow();
        renderButtons();
        return;
      }
      if (r.status === 401 || r.status === 404) return trouble(r.status);
      // 409: the test is gone or the TV just dropped. Send the photo itself.
    }
    return keepUpload(sig);
  }

  async function keepUpload(sig) {
    if (busy) return;
    const mine = draft;
    clearTimeout(keepTimer);
    busy = "use";
    busyProgress = 0;
    renderButtons();
    const r = await sendPhoto("keep", mine);
    busy = null;
    busyProgress = 0;
    if (r && r.status === 201) {
      merge({ id: r.body.id, at: uploads.get(r.body.id)?.at ?? Date.now(), from: r.from, state: "queued", mode: "keep", draft: mine });
      if (draft === mine) {
        save = { id: r.body.id, sig, via: "keep", offline: r.body.online === false };
        if (save.offline) {
          everSaved = true;
          note("Saved. It’ll show up when the TV is back on.", "warn", sig);
        } else {
          clearTimeout(saveTimer);
          // Stored by the relay either way; if the TV is slow to collect it, stop spinning.
          saveTimer = setTimeout(() => {
            if (!save || save.done) return;
            save.offline = true;
            everSaved = true;
            note("Saved. It’ll show up as soon as the TV collects it.", "warn", save.sig);
            renderButtons();
          }, 12_000);
        }
      }
      follow();
    } else if (r) {
      trouble(r.status);
    }
    renderSent();
    renderButtons();
  }

  /** Renders the framing and posts it. Resolves { status, body, from }, or null if it never went. */
  async function sendPhoto(mode, forDraft) {
    let blob;
    try {
      blob = await renderJpeg();
      const thumb = await renderThumb().catch(() => null);
      if (thumb) {
        const old = thumbs.get(forDraft);
        thumbs.set(forDraft, thumb);
        if (old) setTimeout(() => URL.revokeObjectURL(old), 2000);
      }
    } catch {
      note("Couldn’t prepare that photo. Try another one.", "bad");
      return null;
    }
    const from = senderName();
    const dest = pair;
    const res = await new Promise((resolve) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", `/api/tv/${dest.id}/wallpapers`);
      xhr.setRequestHeader("Authorization", `Bearer ${dest.key}`);
      xhr.setRequestHeader("Content-Type", "image/jpeg");
      xhr.setRequestHeader("X-From", encodeURIComponent(from));
      xhr.setRequestHeader("X-Mode", mode);
      xhr.setRequestHeader("X-Draft", forDraft);
      xhr.timeout = 120_000;
      xhr.upload.onprogress = (e) => {
        if (!e.lengthComputable) return;
        busyProgress = e.loaded / e.total;
        renderButtons();
      };
      xhr.onload = () => {
        let body = {};
        try { body = JSON.parse(xhr.responseText || "{}"); } catch { /* keep {} */ }
        resolve({ status: xhr.status, body });
      };
      xhr.onerror = xhr.ontimeout = () => resolve({ status: 0, body: {} });
      xhr.send(blob);
    });
    if (pair !== dest) return null;
    return { ...res, from };
  }

  async function call(path) {
    const dest = pair;
    try {
      const r = await fetch(`/api/tv/${dest.id}/${path}`, { method: "POST", headers: { Authorization: `Bearer ${dest.key}` } });
      let body = {};
      try { body = await r.json(); } catch { /* keep {} */ }
      return { status: r.status, body };
    } catch {
      return { status: 0, body: {} };
    }
  }

  function trouble(status) {
    switch (status) {
      case 401: unpair("reset"); return;
      case 404: unpair("unknown"); return;
      case 409: note("The TV is off. Test needs the TV on.", "warn"); return;
      case 413: note("That photo is too big to send.", "bad"); return;
      case 415: note("The TV can’t show that kind of file.", "bad"); return;
      case 429: note("The TV already has a lot of photos waiting. Turn it on, then try again.", "bad"); return;
      case 0: note("Couldn’t reach the TV’s server. Check your connection and try again.", "bad"); return;
      default: note(`That didn’t work (${status}). Try again.`, "bad");
    }
  }

  /** Moves the buttons along as the relay reports the test and the save. */
  function follow() {
    if (test) {
      const tu = uploads.get(test.id);
      if (tu && (tu.state === "shown" || tu.state === "kept") && !test.landed) {
        test.landed = true;
        onTv = { sig: test.sig, at: Date.now(), test: true };
        setTimeout(renderButtons, TEST_LASTS + 50);
        if (!save || save.id !== test.id) {
          if (tu.visible === false) note("The TV is in another app. The test shows when it’s back home.", "warn", test.sig);
          else { landed(); note("This is a test. Use this to keep it.", "", test.sig); }
        }
      } else if (tu && tu.state === "failed" && !test.failed && !test.landed) {
        test.failed = true;
        note("That test didn’t reach the TV. Try again.", "warn");
      }
    }
    if (save) {
      const su = uploads.get(save.id);
      if (su && save.via === "test") {
        if (su.state === "kept" && !save.done) {
          save.done = true;
          everSaved = true;
          clearTimeout(keepTimer);
          onTv = { sig: save.sig, at: Date.now(), test: false };
          landed();
          note("Saved. It’s one of the TV’s photos now.", "good", save.sig);
        } else if (su.state === "failed" && !save.done && !save.fellBack) {
          // The TV no longer had the test: send the framing as it is now.
          save.fellBack = true;
          clearTimeout(keepTimer);
          save = null;
          keepUpload(framing());
        }
      } else if (su) {
        if ((su.state === "delivered" || su.state === "shown") && !save.done) {
          save.done = true;
          everSaved = true;
          clearTimeout(saveTimer);
          landed();
          note("Saved. It’s one of the TV’s photos now.", "good", save.sig);
        }
        // Once, when it first goes up: a later test of another framing is what is on the TV after that.
        if (su.state === "shown" && su.visible !== false && !save.onScreen) {
          save.onScreen = true;
          onTv = { sig: save.sig, at: Date.now(), test: false };
        }
        if (su.state === "failed" && !save.done) {
          // Nothing new was saved; an earlier save of this photo, if any, is still there.
          clearTimeout(saveTimer);
          save = null;
          note("The TV couldn’t save that photo. Try again.", "bad");
        }
      }
    }
    renderButtons();
  }

  /** A sheen across the preview and a tick: it is on the TV. */
  function landed() {
    screen.classList.remove("shown");
    void screen.offsetWidth; // restart the animation
    screen.classList.add("shown");
    try { navigator.vibrate?.(28); } catch { /* not here */ }
    setTimeout(() => screen.classList.remove("shown"), 2600);
  }

  // ---------------------------------------------------------------- who is sending

  function guessDevice() {
    const ua = navigator.userAgent || "";
    if (/iPhone/.test(ua)) return "iPhone";
    if (/iPad/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return "iPad";
    if (/Android/.test(ua)) return /Mobile/.test(ua) ? "Android phone" : "Android tablet";
    if (/Macintosh|Mac OS X/.test(ua)) return "Mac";
    return "PC";
  }

  function senderName() {
    const s = store.get("liquid.from");
    return typeof s === "string" && s.trim() ? s.trim().slice(0, 40) : guessDevice();
  }

  $("rename").addEventListener("click", () => {
    const v = window.prompt("What should the TV call this phone?", senderName());
    if (v === null) return;
    const name = v.trim().slice(0, 40);
    if (name) store.set("liquid.from", name);
    else store.del("liquid.from");
    $("rename").textContent = senderName();
  });

  // ---------------------------------------------------------------- page events

  $("file").addEventListener("change", (e) => {
    const f = e.target.files && e.target.files[0];
    if (f) load(f);
  });
  $("pick-empty").addEventListener("click", choose);
  $("pick-again").addEventListener("click", choose);

  // Drop or paste a photo (a laptop).
  const imageIn = (list) => [...(list || [])].find((f) => !f.type || f.type.startsWith("image/"));
  const hasFiles = (e) => [...(e.dataTransfer?.types || [])].includes("Files");
  let dragDepth = 0;
  window.addEventListener("dragenter", (e) => {
    if (!pair || !hasFiles(e)) return;
    e.preventDefault();
    dragDepth++;
    screen.classList.add("dragging");
  });
  window.addEventListener("dragover", (e) => { if (hasFiles(e)) e.preventDefault(); });
  window.addEventListener("dragleave", () => {
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) screen.classList.remove("dragging");
  });
  window.addEventListener("drop", (e) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    dragDepth = 0;
    screen.classList.remove("dragging");
    const f = imageIn(e.dataTransfer.files);
    if (f && pair) load(f);
  });
  window.addEventListener("paste", (e) => {
    const f = imageIn(e.clipboardData?.files);
    if (f && pair) {
      e.preventDefault();
      load(f);
    }
  });

  // Back from the background: the socket may have died quietly (iOS closes them).
  function wake() {
    if (!pair || document.hidden) return;
    if (!ws) {
      retries = 0;
      connect();
      return;
    }
    if (ws.readyState === WebSocket.OPEN) {
      const sock = ws;
      const asked = Date.now();
      try { sock.send("ping"); } catch { /* closing */ }
      setTimeout(() => { if (ws === sock && lastHeard < asked) abandon(sock); }, 4000);
    }
  }
  document.addEventListener("visibilitychange", wake);
  window.addEventListener("pageshow", wake);
  window.addEventListener("online", wake);
  window.addEventListener("hashchange", start);

  new ResizeObserver(sizeScreen).observe(screen);

  // ---------------------------------------------------------------- go

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  app.dataset.photo = "no";
  if (finePointer) $("hint").textContent = "Drag to frame it. Scroll to zoom, double-click to reset.";
  setChrome(store.get("liquid.chrome") !== false);
  $("rename").textContent = senderName();
  renderButtons();
  tickClock();
  start();
})();
