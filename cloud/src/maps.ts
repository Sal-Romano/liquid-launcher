/**
 * Google Maps Platform behind the relay: where a photo was taken, from its coordinates or from a
 * name (a wallpaper called "aoraki-mount-cook"), and a small dark map around it. The key is a
 * Worker secret; the TV and the phone only ever talk to the relay.
 */

export type Place = {
  /** The most telling name: a natural feature or landmark for a name lookup, else the town or region. */
  name: string;
  locality: string | null;
  region: string | null;
  country: string | null;
  /** ISO 3166-1 alpha-2, for a flag. */
  countryCode: string | null;
  lat: number;
  lon: number;
};

type Component = { long_name: string; short_name: string; types: string[] };
type Result = {
  address_components: Component[];
  geometry: { location: { lat: number; lng: number } };
  types: string[];
};
type Geocode = { status: string; error_message?: string; results: Result[] };

export type Lookup = { status: number; place?: Place; error?: string };

/** Coordinates to a place ([q] null), or a name to a place and its coordinates. */
export async function lookupPlace(key: string, lat: number | null, lon: number | null, q: string | null): Promise<Lookup> {
  const reverse = lat !== null && lon !== null;
  const query = reverse ? `latlng=${lat.toFixed(5)},${lon.toFixed(5)}` : `address=${encodeURIComponent(q ?? "")}`;
  const r = await fetch(`https://maps.googleapis.com/maps/api/geocode/json?${query}&language=en&key=${key}`);
  if (!r.ok) return { status: 502, error: `Google answered ${r.status}` };
  const data = await r.json<Geocode>();
  if (data.status === "ZERO_RESULTS" || data.results.length === 0) return { status: 404, error: "no such place" };
  if (data.status !== "OK") return { status: 502, error: `Google said ${data.status}${data.error_message ? `: ${data.error_message}` : ""}` };

  // Reverse results run from the street address up to the country; a name lookup's first result
  // is the thing named. Components are searched across all results, most specific first.
  const all = (reverse ? data.results : data.results.slice(0, 1)).flatMap((x) => x.address_components);
  const find = (...types: string[]) => {
    for (const t of types) {
      const c = all.find((x) => x.types.includes(t));
      if (c) return c;
    }
    return null;
  };
  const country = find("country");
  const region = find("administrative_area_level_1");
  const locality = find("locality", "postal_town", "administrative_area_level_3", "administrative_area_level_2", "colloquial_area");
  // A name lookup that found a landmark or a natural feature keeps that name ("Aoraki / Mount Cook").
  const first = data.results[0];
  const named = !reverse && first.address_components[0] &&
    first.address_components[0].types.some((t) => ["natural_feature", "park", "point_of_interest", "establishment", "tourist_attraction", "colloquial_area"].includes(t))
    ? first.address_components[0]
    : null;
  const where = reverse ? { lat: lat!, lon: lon! } : { lat: first.geometry.location.lat, lon: first.geometry.location.lng };
  const name = named?.long_name ?? locality?.long_name ?? region?.long_name ?? country?.long_name;
  if (!name) return { status: 404, error: "no such place" };
  return {
    status: 200,
    place: {
      name,
      locality: locality?.long_name ?? null,
      region: region?.long_name ?? null,
      country: country?.long_name ?? null,
      countryCode: country?.short_name ?? null,
      lat: Math.round(where.lat * 1e5) / 1e5,
      lon: Math.round(where.lon * 1e5) / 1e5,
    },
  };
}

/**
 * A dark, quiet map centred on the place, zoomed out to show the neighbouring countries: no
 * roads, towns or icons, country borders and names only, the colours of the launcher's glass.
 * No marker: the TV draws its own glowing one at the centre. 1280x720 (640x360 at scale 2).
 */
const STYLE = [
  "element:geometry|color:0x1c2331",
  "element:labels.icon|visibility:off",
  "element:labels.text.fill|color:0x9aa6bd",
  "element:labels.text.stroke|color:0x141a26|weight:2",
  "feature:administrative|element:geometry|visibility:off",
  "feature:administrative.country|element:geometry.stroke|visibility:on|color:0x6b7a99|weight:1",
  "feature:administrative.province|visibility:off",
  "feature:administrative.locality|visibility:off",
  "feature:administrative.neighborhood|visibility:off",
  "feature:administrative.land_parcel|visibility:off",
  "feature:landscape|element:geometry|color:0x232c3d",
  "feature:poi|visibility:off",
  "feature:road|visibility:off",
  "feature:transit|visibility:off",
  "feature:water|element:geometry|color:0x0b1220",
  "feature:water|element:labels|visibility:off",
];

export type MapImage = { status: number; bytes?: ArrayBuffer; error?: string };

export async function fetchMap(key: string, lat: number, lon: number, zoom: number): Promise<MapImage> {
  const style = STYLE.map((s) => `&style=${encodeURIComponent(s)}`).join("");
  const r = await fetch(
    `https://maps.googleapis.com/maps/api/staticmap?center=${lat.toFixed(4)},${lon.toFixed(4)}&zoom=${zoom}` +
      `&size=640x360&scale=2&format=png&maptype=roadmap&language=en${style}&key=${key}`,
  );
  if (!r.ok) return { status: 502, error: `Google answered ${r.status}` };
  if (!(r.headers.get("Content-Type") ?? "").startsWith("image/")) return { status: 502, error: "Google sent no image" };
  return { status: 200, bytes: await r.arrayBuffer() };
}
