// When, where and with what a photo was taken, read from its EXIF before the canvas render strips
// it. JPEG (the APP1 "Exif" segment) and HEIC/HEIF/AVIF (the "Exif" item). No libraries.
//
//   LiquidExif.read(file) → Promise<{ takenAt?, lat?, lon?, camera? }>
//
// takenAt: "YYYY-MM-DDTHH:MM:SS", plus "+HH:MM" when the camera wrote OffsetTimeOriginal (EXIF
// local time, never guessed into a time zone). lat/lon: degrees, signed by their refs, five
// decimals; (0, 0) is dropped. camera: "Make Model" without the make said twice.
// Everything is bounds-checked; anything unexpected means no details, never an error.
"use strict";

(function (root) {
  /** Enough for any JPEG's APP1 (64 KB at most) and a HEIC's meta box. */
  const HEAD = 256 * 1024;
  /** An EXIF item bigger than this is not one we want to read. */
  const ITEM_MAX = 1 << 20;

  async function read(file) {
    try {
      const head = new DataView(await file.slice(0, HEAD).arrayBuffer());
      if (head.byteLength >= 4 && head.getUint16(0) === 0xffd8) {
        const t = jpegTiff(head);
        return t ? details(head, t.start, t.end) : {};
      }
      if (isHeif(head)) {
        const loc = heifExif(head);
        if (!loc || loc.length < 12 || loc.length > ITEM_MAX || loc.offset + loc.length > file.size) return {};
        const item = new DataView(await file.slice(loc.offset, loc.offset + loc.length).arrayBuffer());
        // The item starts with the offset of its TIFF header, counted from just after these 4 bytes.
        let start = 4 + item.getUint32(0);
        if (!tiffAt(item, start)) start = findTiff(item, 4, Math.min(item.byteLength, 64));
        return start >= 0 ? details(item, start, item.byteLength) : {};
      }
    } catch {
      // Malformed: no details.
    }
    return {};
  }

  // ---- JPEG

  /** The TIFF data inside the first APP1 "Exif\0\0" segment, or null. */
  function jpegTiff(v) {
    const n = v.byteLength;
    let off = 2;
    while (off + 4 <= n) {
      if (v.getUint8(off) !== 0xff) return null;
      const marker = v.getUint8(off + 1);
      if (marker === 0xff) { off += 1; continue; } // fill byte
      if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) { off += 2; continue; } // no length
      if (marker === 0xda || marker === 0xd9) return null; // the image data: metadata comes before it
      const len = v.getUint16(off + 2);
      if (len < 2) return null;
      if (marker === 0xe1 && len >= 14 && off + 10 <= n && ascii(v, off + 4, 6) === "Exif\0\0") {
        return { start: off + 10, end: Math.min(n, off + 2 + len) };
      }
      off += 2 + len; // another APP segment (JFIF, XMP, ICC…)
    }
    return null;
  }

  // ---- HEIF (ISO base media file format)

  function isHeif(v) {
    if (v.byteLength < 16 || ascii(v, 4, 4) !== "ftyp") return false;
    const end = Math.min(v.byteLength, v.getUint32(0));
    for (let at = 8; at + 4 <= end; at += 4) {
      if (at === 12) continue; // minor version
      if (["heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "avif"].includes(ascii(v, at, 4))) return true;
    }
    return false;
  }

  /** Where the "Exif" item's bytes are in the file: meta → iinf (which item) → iloc (where). */
  function heifExif(v) {
    const meta = box(v, 0, v.byteLength, "meta");
    if (!meta) return null;
    const inside = meta.body + 4; // a full box: version and flags first
    const iinf = box(v, inside, meta.end, "iinf");
    const iloc = box(v, inside, meta.end, "iloc");
    if (!iinf || !iloc) return null;
    const id = exifItem(v, iinf);
    return id === null ? null : itemAt(v, iloc, id, meta);
  }

  function box(v, from, to, type) {
    let at = from;
    while (at + 8 <= to) {
      let size = v.getUint32(at);
      let header = 8;
      if (size === 1) {
        if (at + 16 > to) return null;
        size = v.getUint32(at + 8) * 2 ** 32 + v.getUint32(at + 12);
        header = 16;
      } else if (size === 0) {
        size = to - at;
      }
      if (size < header) return null;
      if (ascii(v, at + 4, 4) === type) return { at, body: at + header, end: Math.min(to, at + size) };
      at += size;
    }
    return null;
  }

  function exifItem(v, iinf) {
    const version = v.getUint8(iinf.body);
    let at = iinf.body + 4;
    const count = version === 0 ? v.getUint16(at) : v.getUint32(at);
    at += version === 0 ? 2 : 4;
    for (let i = 0; i < count && at + 12 <= iinf.end; i++) {
      const size = v.getUint32(at);
      if (size < 12 || at + size > iinf.end) return null;
      if (ascii(v, at + 4, 4) === "infe") {
        const ver = v.getUint8(at + 8);
        if (ver >= 2) {
          let p = at + 12;
          const id = ver === 2 ? v.getUint16(p) : v.getUint32(p);
          p += (ver === 2 ? 2 : 4) + 2; // item id, protection index
          if (p + 4 <= at + size && ascii(v, p, 4) === "Exif") return id;
        }
      }
      at += size;
    }
    return null;
  }

  function itemAt(v, iloc, id, meta) {
    const version = v.getUint8(iloc.body);
    let p = iloc.body + 4;
    const num = (n) => {
      if (n !== 0 && n !== 2 && n !== 4 && n !== 8) throw new Error("iloc field size");
      if (p + n > iloc.end) throw new Error("iloc overrun");
      let x = 0;
      for (let i = 0; i < n; i++) x = x * 256 + v.getUint8(p++);
      return x;
    };
    const sizes = v.getUint8(p);
    const sizes2 = v.getUint8(p + 1);
    p += 2;
    const offSize = sizes >> 4;
    const lenSize = sizes & 15;
    const baseSize = sizes2 >> 4;
    const idxSize = version >= 1 ? sizes2 & 15 : 0;
    const count = num(version < 2 ? 2 : 4);
    for (let i = 0; i < count; i++) {
      const itemId = num(version < 2 ? 2 : 4);
      const method = version >= 1 ? num(2) & 15 : 0;
      num(2); // data reference index
      const base = num(baseSize);
      const extents = num(2);
      let first = null;
      for (let e = 0; e < extents; e++) {
        if (idxSize) num(idxSize);
        const offset = num(offSize);
        const length = num(lenSize);
        if (e === 0) first = { offset, length };
      }
      if (itemId !== id) continue;
      if (!first || extents !== 1) return null;
      if (method === 0) return { offset: base + first.offset, length: first.length };
      if (method === 1) {
        // In the meta box's idat, which the head we read contains.
        const idat = box(v, meta.body + 4, meta.end, "idat");
        return idat ? { offset: idat.body + base + first.offset, length: first.length } : null;
      }
      return null;
    }
    return null;
  }

  function tiffAt(v, at) {
    if (at < 0 || at + 8 > v.byteLength) return false;
    const bo = v.getUint16(at);
    return (bo === 0x4949 && v.getUint16(at + 2, true) === 42) || (bo === 0x4d4d && v.getUint16(at + 2, false) === 42);
  }

  function findTiff(v, from, to) {
    for (let at = from; at + 8 <= to; at++) if (tiffAt(v, at)) return at;
    return -1;
  }

  // ---- TIFF / EXIF

  /** IFD0 (make, model) → the Exif IFD (date) and the GPS IFD (where). Each part on its own. */
  function details(v, start, end) {
    const t = tiff(v, start, end);
    if (!t) return {};
    const out = {};
    let ifd0;
    try { ifd0 = t.ifd(t.u32(start + 4)); } catch { return {}; }
    try {
      const camera = cameraName(t.ascii(ifd0.get(0x010f)), t.ascii(ifd0.get(0x0110)));
      if (camera) out.camera = camera;
    } catch { /* no camera */ }
    try {
      const at = t.pointer(ifd0.get(0x8769));
      if (at) {
        const exif = t.ifd(at);
        const when = takenAt(t.ascii(exif.get(0x9003)) || t.ascii(exif.get(0x9004)), t.ascii(exif.get(0x9011)));
        if (when) out.takenAt = when;
      }
    } catch { /* no date */ }
    try {
      const at = t.pointer(ifd0.get(0x8825));
      if (at) {
        const gps = t.ifd(at);
        const lat = coordinate(t.dms(gps.get(0x0002)), t.ascii(gps.get(0x0001)), "N", "S", 90);
        const lon = coordinate(t.dms(gps.get(0x0004)), t.ascii(gps.get(0x0003)), "E", "W", 180);
        if (lat !== null && lon !== null && !(lat === 0 && lon === 0)) {
          out.lat = Math.round(lat * 1e5) / 1e5;
          out.lon = Math.round(lon * 1e5) / 1e5;
        }
      }
    } catch { /* no place */ }
    return out;
  }

  function tiff(v, start, end) {
    if (start < 0 || start + 8 > end || end > v.byteLength) return null;
    const bo = v.getUint16(start);
    const le = bo === 0x4949 ? true : bo === 0x4d4d ? false : null;
    if (le === null || v.getUint16(start + 2, le) !== 42) return null;
    const check = (at, n) => {
      if (at < start || n < 0 || at + n > end) throw new Error("outside the EXIF data");
    };
    const t = {
      u16(at) { check(at, 2); return v.getUint16(at, le); },
      u32(at) { check(at, 4); return v.getUint32(at, le); },
      /** An IFD at [rel] (from the TIFF header): tag → { type, count, at } where at is the 4-byte value field. */
      ifd(rel) {
        const at = start + rel;
        const n = t.u16(at);
        if (n > 1000) throw new Error("not an IFD");
        check(at + 2, n * 12);
        const map = new Map();
        for (let i = 0; i < n; i++) {
          const e = at + 2 + i * 12;
          map.set(v.getUint16(e, le), { type: v.getUint16(e + 2, le), count: v.getUint32(e + 4, le), at: e + 8 });
        }
        return map;
      },
      /** The bytes of an entry's value: in the entry when they fit in 4 bytes, else at its offset. */
      value(entry, unit) {
        const size = entry.count * unit;
        if (size > 4096) throw new Error("too long");
        const at = size <= 4 ? entry.at : start + t.u32(entry.at);
        check(at, size);
        return { at, size };
      },
      ascii(entry) {
        if (!entry || (entry.type !== 2 && entry.type !== 7) || entry.count === 0) return "";
        const { at, size } = t.value(entry, 1);
        let s = "";
        for (let i = 0; i < size; i++) {
          const c = v.getUint8(at + i);
          if (c === 0) break;
          s += String.fromCharCode(c);
        }
        return s.trim();
      },
      pointer(entry) {
        if (!entry || entry.count !== 1 || (entry.type !== 4 && entry.type !== 13)) return 0;
        return t.u32(entry.at);
      },
      /** Degrees, minutes, seconds as three rationals. */
      dms(entry) {
        if (!entry || entry.type !== 5 || entry.count < 3) return null;
        const { at } = t.value(entry, 8);
        const parts = [];
        for (let i = 0; i < 3; i++) {
          const num = v.getUint32(at + i * 8, le);
          const den = v.getUint32(at + i * 8 + 4, le);
          if (den === 0) {
            if (i === 0) return null; // no degrees, no place
            parts.push(0); // some cameras write 0/0 for unused minutes or seconds
            continue;
          }
          parts.push(num / den);
        }
        return parts;
      },
    };
    return t;
  }

  function coordinate(dms, ref, plus, minus, max) {
    if (!dms || (ref !== plus && ref !== minus)) return null;
    const [d, m, s] = dms;
    const deg = d + m / 60 + s / 3600;
    if (!Number.isFinite(deg) || deg > max || m >= 60 || s >= 60) return null;
    return ref === minus ? -deg : deg;
  }

  function takenAt(stamp, offset) {
    const m = /^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/.exec(stamp || "");
    if (!m) return null;
    const [y, mo, d, h, mi, s] = m.slice(1).map(Number);
    if (y < 1900 || mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || s > 60) return null;
    const local = `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]}`;
    return /^[+-]\d{2}:\d{2}$/.test(offset || "") ? local + offset : local;
  }

  /** "Apple" + "iPhone 15 Pro" → "iPhone 15 Pro"; "NIKON CORPORATION" + "NIKON Z 6" → "NIKON Z 6"; "Google" + "Pixel 8" → "Google Pixel 8". */
  function cameraName(make, model) {
    const clean = (x) => x.replace(/[^\x20-\x7e]/g, "").replace(/\s+/g, " ").trim();
    let mk = clean(make).replace(/[\s,]+(corporation|corp\.?|co\.?,? ?ltd\.?|company|imaging corp\.?|inc\.?)$/i, "").trim();
    const md = clean(model);
    if (mk && mk === mk.toLowerCase()) mk = mk.charAt(0).toUpperCase() + mk.slice(1);
    if (!md) return mk.slice(0, 48) || null;
    if (!mk || /^apple$/i.test(mk)) return md.slice(0, 48);
    if (md.toLowerCase().startsWith(mk.split(" ")[0].toLowerCase())) return md.slice(0, 48);
    return `${mk} ${md}`.slice(0, 48);
  }

  function ascii(v, at, n) {
    let s = "";
    for (let i = 0; i < n; i++) s += String.fromCharCode(v.getUint8(at + i));
    return s;
  }

  root.LiquidExif = { read, cameraName, takenAt };
})(typeof window !== "undefined" ? window : globalThis);
