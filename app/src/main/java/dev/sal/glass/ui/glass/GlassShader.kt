package dev.sal.glass.ui.glass

/**
 * GLSL ES 3.00 for the glass. Runs on the TV's Mali-G52 (GLES 3.2) and the emulator (GLES 3.0).
 *
 * Model, after m2-md's screen-space refraction reference (MIT) and TiHxdvt's liquid-glass-webgl
 * (MIT): the panel is a clear slab with a quarter-circle bevel of width uBevel around its edge.
 * The bevel's height profile gives a surface normal; refract() with IOR 1.52 bends the view ray
 * and the exit point decides which backdrop pixel shows. The whole slab also magnifies what is
 * behind it slightly (uMag), like a convex lens.
 *
 * What shows through is the live photo itself, sampled from its mipmap chain: sharp at the rim,
 * where the lens bends it, and softened toward the middle (uLod), which is how a thick clear slab
 * looks rather than a frosted one. Sheets, which carry text, mix a deep pre-rendered blur in
 * (uDeepMix). Dispersion samples three wavelengths.
 *
 * The light is the photo's own. The slab mirrors its surroundings: the rim reflects the photo
 * beyond its edge (sky above, ground below), which is what gives glass its coloured edge. One lamp
 * hangs where the photo's light is (uLamp, found per photo by SceneLight) and puts a small glint
 * of that colour on the corners of the rim that face it; every panel sees the lamp from its own
 * angle, so no two tiles carry the same glint, and the shadow leans away from it. As a tile lifts
 * it leans back a few degrees: a band of what lies above it slides down its face, and the glint
 * and the bent backdrop move with it.
 *
 * Both backdrops are given for the outgoing and incoming photo so the glass crossfades with the
 * wallpaper instead of snapping. Outside a crossfade only one photo is fetched, and in the flat
 * middle of a slab at rest the rim's samples are skipped: the pixels are identical either way.
 */
object GlassShader {
    const val VERTEX = """#version 300 es
in vec2 aPos;          // panel quad in px, with margin for the shadow
uniform vec2 uRes;
out vec2 vFrag;
void main() {
    vFrag = aPos;
    vec2 ndc = aPos / uRes * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);   // y down like Android
}
"""

    const val FRAGMENT = """#version 300 es
precision highp float;
uniform sampler2D uSharpA;  // outgoing photo, mipmapped (screen UVs 0..1)
uniform sampler2D uDeepA;   // its deep blur
uniform sampler2D uSharpB;  // incoming photo, mipmapped
uniform sampler2D uDeepB;   // its deep blur
uniform float uMix;         // 0 = A only, 1 = B only (wallpaper crossfade)
uniform float uZoom;        // Ken Burns scale of the photo about the screen centre
uniform vec2  uRes;         // px
uniform vec4  uRect;        // center.xy, half.xy (px)
uniform float uRadius;      // corner px
uniform float uBevel;       // bevel ring width px
uniform float uThick;       // slab thickness px (refraction depth)
uniform float uLift;        // 0..1 focus
uniform float uAlpha;       // 0..1 panel fade
uniform float uLod;         // mip level of the photo in the slab's middle: 0 = clear, 5 = heavy frost
uniform float uDeepMix;     // 0 = live photo, 1 = deep pre-rendered blur in the middle (sheets)
uniform float uTint;        // material tint strength in the middle
uniform float uDark;        // 0 = white tint, 1 = smoked tint (bright backdrops)
uniform float uMag;         // convex magnification of the backdrop, about the slab centre
uniform vec2  uLamp;        // where the photo's light is, photo coordinates 0..1 (may lie outside)
uniform vec3  uLampCol;     // its colour, brightest channel 1
uniform vec2  uTilt;        // slab rotation about its centre, radians: x = top edge away, y = right edge away
uniform float uCam;         // camera distance that projects the rotation, px (Compose's, 576)
in vec2 vFrag;
out vec4 outColor;

const float IOR = 1.52;
const vec3  EYE = vec3(0.0, 0.0, 1.0);
const vec3  LUMA = vec3(0.299, 0.587, 0.114);
const float LEAN = 0.17;    // radians a fully lifted tile leans back, top edge away (about 10 deg)

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;
}
// Outward direction of the rounded box at p (the SDF's gradient), inside and out.
vec2 boxNormal(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    vec2 g = (max(q.x, q.y) > 0.0) ? normalize(max(q, vec2(0.0))) : ((q.x > q.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    return g * sign(p);
}
// 0 at the edge, 1 where the bevel ends: quarter circle, so the normal tilts most at the rim.
float glassHeight(float d, float w) {
    float x = clamp(-d / max(w, 1e-3), 0.0, 1.0);
    float t = 1.0 - x;
    return sqrt(max(1.0 - t * t, 0.0));
}
vec3 glassNormal(vec2 p, vec2 hs, float r, float bevel, float thick) {
    const float e = 1.0;
    float hx = glassHeight(sdRoundedBox(p + vec2(e, 0.0), hs, r), bevel) - glassHeight(sdRoundedBox(p - vec2(e, 0.0), hs, r), bevel);
    float hy = glassHeight(sdRoundedBox(p + vec2(0.0, e), hs, r), bevel) - glassHeight(sdRoundedBox(p - vec2(0.0, e), hs, r), bevel);
    float k = thick / (2.0 * e);
    return normalize(vec3(-hx * k, -hy * k, 1.0));
}
// Lean the slab back about its horizontal axis: the top edge away from the viewer.
vec3 leanBack(vec3 n, float a) {
    float c = cos(a), s = sin(a);
    return vec3(n.x, n.y * c - n.z * s, n.y * s + n.z * c);
}
// The slab's 3D rotation (uTilt), z toward the viewer: about y first, then x, as the platform
// composes rotationX and rotationY. Columns are the slab's own axes and normal in screen space.
mat3 slabRotation() {
    float cx = cos(uTilt.x), sx = sin(uTilt.x), cy = cos(uTilt.y), sy = sin(uTilt.y);
    mat3 rx = mat3(1.0, 0.0, 0.0,   0.0, cx, sx,   0.0, -sx, cx);
    mat3 ry = mat3(cy, 0.0, -sy,   0.0, 1.0, 0.0,   sy, 0.0, cy);
    return rx * ry;
}
// The point of the rotated slab under a screen pixel, in the slab's own px (its centre at the
// origin), seen by a pinhole camera uCam px in front of the centre, which is how Compose draws
// the icon inside the same rotation. Screen px s relative to the centre.
vec2 slabPoint(vec2 s, mat3 R) {
    vec3 N = R[2];
    // Ray C + t (S - C) with C = (0, 0, uCam), S = (s, 0); it meets the plane N . P = 0 at
    // t = N.z uCam / (N.z uCam - N.xy . s).
    float t = N.z * uCam / (N.z * uCam - (N.x * s.x + N.y * s.y));
    vec3 P = vec3(t * s, uCam * (1.0 - t));
    return vec2(dot(P, R[0]), dot(P, R[1]));
}
vec2 refractOffset(vec3 n, float eta, float depth) {
    vec3 r = refract(vec3(0.0, 0.0, -1.0), n, eta);
    if (dot(r, r) == 0.0) return vec2(0.0);
    return r.xy * depth / max(abs(r.z), 1e-3);
}
// Screen px → photo UV, undoing the Ken Burns zoom so the glass stays registered to the photo.
vec2 photoUv(vec2 px) {
    vec2 uv = px / uRes;
    return (uv - 0.5) / uZoom + 0.5;
}
// Outside a crossfade uMix is exactly 0, and the incoming photo is not fetched at all.
vec3 photoAt(vec2 uv, float lod) {
    vec3 a = textureLod(uSharpA, uv, lod).rgb;
    if (uMix <= 0.0) return a;
    return mix(a, textureLod(uSharpB, uv, lod).rgb, uMix);
}
vec3 sharpAt(vec2 px, float lod) { return photoAt(photoUv(px), lod); }
// The mirrored surroundings: a lookup past the photo's edge folds back inside (mirror wrap)
// instead of smearing the last texel row along a rim that sits near the screen edge.
vec3 envAt(vec2 px, float lod) {
    vec2 uv = photoUv(px);
    return photoAt(1.0 - abs(1.0 - abs(uv)), lod);
}
vec3 deepAt(vec2 px) {
    vec2 uv = photoUv(px);
    vec3 a = texture(uDeepA, uv).rgb;
    if (uMix <= 0.0) return a;
    return mix(a, texture(uDeepB, uv).rgb, uMix);
}
// Soft focus: four taps at the mip level, half a texel apart in a diamond, so the chain's box
// steps average into a smooth blur instead of banding on hard verticals.
vec3 softAt(vec2 px, float lod) {
    float t = exp2(lod) * 0.5;
    return 0.25 * (sharpAt(px + vec2( t, 0.0), lod) + sharpAt(px + vec2(-t, 0.0), lod) +
                   sharpAt(px + vec2(0.0,  t), lod) + sharpAt(px + vec2(0.0, -t), lod));
}
// The lamp in screen px: the photo's light, through the Ken Burns zoom, in front of the glass by
// just under half a screen width. Near enough that every panel sees it from its own angle.
vec3 lampDir(vec2 px) {
    vec2 lampPx = ((uLamp - 0.5) * uZoom + 0.5) * uRes;
    return normalize(vec3(lampPx, uRes.x * 0.45) - vec3(px, 0.0));
}

void main() {
    // p: where this pixel lands on the slab, in the slab's own px. With a tilt the slab is a
    // rotated plane in perspective; the shape, bevel and lighting live on that plane, while the
    // backdrop is still sampled where the pixel is on screen, since the photo does not rotate.
    vec2 s = vFrag - uRect.xy;
    bool tilted = dot(uTilt, uTilt) > 0.0;
    mat3 R = mat3(1.0);
    vec2 p = s;
    if (tilted) { R = slabRotation(); p = slabPoint(s, R); }
    float r = min(uRadius, min(uRect.z, uRect.w) - 1.0);
    float sd = sdRoundedBox(p, uRect.zw, r);
    vec3 L = lampDir(vFrag);

    // Shadow outside the slab: soft, leaning away from the lamp, longer and deeper when lifted.
    // Only the margin and the anti-aliased edge use it.
    float shadow = 0.0;
    if (sd > -0.75) {
        vec2 shadowOff = -L.xy / max(L.z, 0.3) * (4.4 + 7.2 * uLift);
        float shadowD = sdRoundedBox(p - shadowOff, uRect.zw, r);
        shadow = (1.0 - smoothstep(0.0, 18.0 + 14.0 * uLift, shadowD)) * (0.30 + 0.22 * uLift);
    }
    if (sd > 0.75) {
        outColor = vec4(0.0, 0.0, 0.0, shadow * uAlpha);
        return;
    }

    // Past the bevel the slab is flat: its normal is straight on and, at rest, nothing below
    // depends on the samples the rim needs, so that work is skipped there (same pixels).
    bool middle = sd < -uBevel - 1.0;   // ('flat' is a GLSL keyword)
    float lean = LEAN * uLift;
    vec3 n0 = vec3(0.0, 0.0, 1.0);
    if (!middle) n0 = glassNormal(p, uRect.zw, r, uBevel, uThick);
    // The surface normal in screen space: the slab's rotation, then the lighting lean.
    vec3 n = leanBack(tilted ? R * n0 : n0, lean);
    float depth = uThick * (1.0 + 0.5 * uLift);
    float ring = 1.0 - glassHeight(sd, uBevel);               // 1 at the edge, 0 inside
    float inner = 1.0 - ring;

    // Where the view ray exits: the convex magnification over the whole slab, then the bend of
    // the bevel (and of the lean, which shifts the whole picture a little), per wavelength.
    vec2 base = uRect.xy + s / uMag;
    vec2 offR = vec2(0.0), offG = vec2(0.0), offB = vec2(0.0);
    if (!middle || uLift > 0.0) {
        offR = refractOffset(n, 1.0 / (IOR - 0.02), depth);
        offG = refractOffset(n, 1.0 / IOR, depth);
        offB = refractOffset(n, 1.0 / (IOR + 0.02), depth);
    }
    // The rim is sharp and dispersed, where the lens does the work; the middle is a soft focus.
    float lod = uLod * smoothstep(0.0, 1.0, inner);
    vec3 soft = softAt(base + offG, lod);
    float wRim = smoothstep(0.1, 0.55, inner);
    vec3 back = soft;
    if (wRim < 1.0) {
        vec3 rim = vec3(sharpAt(base + offR, 0.0).r, sharpAt(base + offG, 0.0).g, sharpAt(base + offB, 0.0).b);
        back = mix(rim, soft, wRim);
    }
    if (uDeepMix > 0.0) back = mix(back, deepAt(base + offG * 0.6), uDeepMix * inner);

    // Material: a faint milky tint over dark scenes, a smoked one over bright; thinner at the rim.
    vec3 tint = mix(vec3(1.0), vec3(0.08, 0.09, 0.12), uDark);
    vec3 col = mix(back, tint, uTint * (1.0 - 0.6 * ring));
    // Glass makes what is behind it a touch richer.
    col = mix(vec3(dot(col, LUMA)), col, 1.12);
    // The thick edge absorbs a little light just inside the rim, which is what makes it look thick.
    col *= 1.0 - 0.2 * smoothstep(0.25, 0.9, ring);

    // Reflection of the surroundings. The bevel faces outward, so the rim mirrors a compressed
    // strip of the photo past its edge: sky on the top edge, ground on the bottom, softer the
    // more it is compressed. The leaning face mirrors what lies above the tile across its upper
    // half, and that band slides down over it as the tile rises.
    float fromTop = clamp(0.5 - p.y / (2.0 * uRect.w), 0.0, 1.0);   // 1 at the top edge, 0 at the bottom
    // A turning slab mirrors across its whole face for as long as it turns.
    float turn = tilted ? clamp(length(uTilt) * 4.0, 0.0, 1.0) : 0.0;
    float refl = 0.5 * ring + 0.28 * uLift * inner * fromTop * fromTop + 0.18 * turn * inner;
    vec3 env = vec3(0.0);
    if (!middle || uLift > 0.0 || tilted) {
        // The face's mirror direction follows its screen-space normal (rotation, then lean), at
        // twice the physical sweep so the picture visibly travels as the slab turns.
        vec3 Nf = leanBack(tilted ? R[2] : vec3(0.0, 0.0, 1.0), lean);
        vec2 facing = boxNormal(p, uRect.zw, r) * ring + 4.0 * Nf.z * Nf.xy;
        env = envAt(vFrag + facing * uRes.y * 0.25, 1.0 + 4.0 * ring);
    }
    col = mix(col, env * 1.15, refl);

    // The lamp's glint: a small, sharp reflection of the photo's light, in its colour, on the
    // corners of the rim that face it; a straight edge carries almost none, so a lamp in line
    // with an edge never draws a bar along it. The corner test uses the unleaned shape so the
    // lean cannot turn a straight edge into a corner. It moves with the lean.
    float spec = pow(max(dot(reflect(-L, n), EYE), 0.0), 300.0);
    float corner = abs(n0.x * n0.y) / max(dot(n0.xy, n0.xy), 1e-4);   // 0 on a straight edge, 0.5 at 45 deg
    col += mix(vec3(1.0), uLampCol, 0.6) * spec * (0.08 + 1.6 * corner) * (0.9 + 0.6 * uLift) * (0.35 + 0.65 * ring);

    // The hairline every glass edge has: 1.5 px along the rim, brighter on the side that faces
    // the lamp, and half its colour from what the rim mirrors, so it is a wire of glass, not paint.
    float edge = 1.0 - smoothstep(0.0, 1.6, -sd);
    float lit = clamp(0.5 + dot(p, L.xy) / (uRect.z + uRect.w), 0.0, 1.0);
    col += mix(vec3(1.0), env, 0.5) * edge * (0.12 + 0.30 * lit) * (0.8 + 0.4 * uLift);

    // Light entering from the lamp's side: a faint gradient across the slab and a soft band just
    // inside the bevel.
    col += vec3(0.05 * lit);
    float innerBand = 1.0 - smoothstep(0.0, uBevel * 0.9, abs(-sd - uBevel * 0.7));
    col += vec3(0.05 * innerBand * lit * lit * (0.7 + 0.3 * uLift));

    // 1 px anti-aliased edge; the shadow continues outside.
    float cover = 1.0 - smoothstep(-0.75, 0.75, sd);
    float outA = mix(shadow, 1.0, cover) * uAlpha;
    outColor = vec4(col * uAlpha * cover, outA);
}
"""
}
