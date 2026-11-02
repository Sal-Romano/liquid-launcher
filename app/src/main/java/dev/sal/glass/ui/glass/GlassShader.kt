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
 * (uDeepMix). Dispersion samples three wavelengths; a thin Fresnel edge and two speculars (a sharp
 * one from the upper left, a faint one from the lower right) give the polished rim. Both
 * backdrops are given for the outgoing and incoming photo so the glass crossfades with the
 * wallpaper instead of snapping.
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
in vec2 vFrag;
out vec4 outColor;

const float IOR = 1.52;
const vec3  EYE = vec3(0.0, 0.0, 1.0);
const vec3  LIGHT  = normalize(vec3(-0.35, -0.75, 0.56));   // upper-left, like iOS
const vec3  LIGHT2 = normalize(vec3( 0.45,  0.80, 0.40));   // faint fill from the lower right
const vec3  LUMA = vec3(0.299, 0.587, 0.114);

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, vec2(0.0))) - r;
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
vec2 refractOffset(vec3 n, float eta, float depth) {
    vec3 r = refract(vec3(0.0, 0.0, -1.0), n, eta);
    if (dot(r, r) == 0.0) return vec2(0.0);
    return r.xy * depth / max(abs(r.z), 1e-3);
}
float fresnel(float cosT, float f0) {
    float c = clamp(1.0 - cosT, 0.0, 1.0);
    return f0 + (1.0 - f0) * c * c * c * c * c;
}
// Screen px → photo UV, undoing the Ken Burns zoom so the glass stays registered to the photo.
vec2 photoUv(vec2 px) {
    vec2 uv = px / uRes;
    return (uv - 0.5) / uZoom + 0.5;
}
vec3 sharpAt(vec2 px, float lod) {
    vec2 uv = photoUv(px);
    return mix(textureLod(uSharpA, uv, lod).rgb, textureLod(uSharpB, uv, lod).rgb, uMix);
}
vec3 deepAt(vec2 px) {
    vec2 uv = photoUv(px);
    return mix(texture(uDeepA, uv).rgb, texture(uDeepB, uv).rgb, uMix);
}
// Soft focus: four taps at the mip level, half a texel apart in a diamond, so the chain's box
// steps average into a smooth blur instead of banding on hard verticals.
vec3 softAt(vec2 px, float lod) {
    float t = exp2(lod) * 0.5;
    return 0.25 * (sharpAt(px + vec2( t, 0.0), lod) + sharpAt(px + vec2(-t, 0.0), lod) +
                   sharpAt(px + vec2(0.0,  t), lod) + sharpAt(px + vec2(0.0, -t), lod));
}

void main() {
    vec2 p = vFrag - uRect.xy;
    float r = min(uRadius, min(uRect.z, uRect.w) - 1.0);
    float sd = sdRoundedBox(p, uRect.zw, r);

    // Shadow outside the slab: soft, offset down, deeper when lifted.
    float shadowD = sdRoundedBox(p - vec2(0.0, 6.0 + 10.0 * uLift), uRect.zw, r);
    float shadow = (1.0 - smoothstep(0.0, 18.0 + 14.0 * uLift, shadowD)) * (0.30 + 0.22 * uLift);
    if (sd > 0.75) {
        outColor = vec4(0.0, 0.0, 0.0, shadow * uAlpha);
        return;
    }

    vec3 n = glassNormal(p, uRect.zw, r, uBevel, uThick);
    float depth = uThick * (1.0 + 0.5 * uLift);
    float ring = 1.0 - glassHeight(sd, uBevel);               // 1 at the edge, 0 inside
    float inner = 1.0 - ring;

    // Where the view ray exits: the convex magnification over the whole slab, then the bevel's
    // bend, per wavelength. Blue bends most.
    vec2 base = uRect.xy + p / uMag;
    vec2 offR = refractOffset(n, 1.0 / (IOR - 0.02), depth);
    vec2 offG = refractOffset(n, 1.0 / IOR, depth);
    vec2 offB = refractOffset(n, 1.0 / (IOR + 0.02), depth);
    // The rim is sharp and dispersed, where the lens does the work; the middle is a soft focus.
    float lod = uLod * smoothstep(0.0, 1.0, inner);
    vec3 rim = vec3(sharpAt(base + offR, 0.0).r, sharpAt(base + offG, 0.0).g, sharpAt(base + offB, 0.0).b);
    vec3 soft = softAt(base + offG, lod);
    vec3 back = mix(rim, soft, smoothstep(0.1, 0.55, inner));
    if (uDeepMix > 0.0) back = mix(back, deepAt(base + offG * 0.6), uDeepMix * inner);

    // Material: a faint milky tint over dark scenes, a smoked one over bright; thinner at the rim.
    vec3 tint = mix(vec3(1.0), vec3(0.08, 0.09, 0.12), uDark);
    vec3 col = mix(back, tint, uTint * (1.0 - 0.6 * ring));
    // Glass makes what is behind it a touch richer.
    col = mix(vec3(dot(col, LUMA)), col, 1.12);
    // The thick edge absorbs a little light just inside the rim, which is what makes it look thick.
    col *= 1.0 - 0.2 * smoothstep(0.25, 0.9, ring);

    // Lighting: a thin Fresnel edge, a sharp specular from the upper left, a faint fill from the
    // lower right. All of it lives on the bevel; the flat middle stays clear.
    float cosT = clamp(n.z, 0.0, 1.0);
    float f = fresnel(cosT, 0.02);
    float spec1 = pow(max(dot(reflect(-LIGHT, n), EYE), 0.0), 90.0);
    float spec2 = pow(max(dot(reflect(-LIGHT2, n), EYE), 0.0), 60.0) * 0.35;
    col += vec3((f * 0.9 + (spec1 + spec2) * (1.2 + 0.8 * uLift)) * (0.35 + 0.65 * ring));
    // The hairline every glass edge has: 1.5 px along the rim, bright where the light hits
    // (top and left), faint at the bottom right.
    float edge = 1.0 - smoothstep(0.0, 1.6, -sd);
    float lit = clamp(0.5 - (p.x * 0.35 + p.y * 0.75) / (uRect.z + uRect.w), 0.0, 1.0);
    col += vec3(edge * (0.22 + 0.5 * lit) * (0.8 + 0.4 * uLift));

    // Light entering from the top: a faint gradient and a soft band just inside the bevel.
    vec2 g = (p + uRect.zw) / (2.0 * uRect.zw);
    col += vec3(0.05 * (1.0 - clamp(g.x * 0.6 + g.y * 0.9, 0.0, 1.0)));
    float innerBand = 1.0 - smoothstep(0.0, uBevel * 0.9, abs(-sd - uBevel * 0.7));
    float fromTop = clamp(0.5 - p.y / (2.0 * uRect.w), 0.0, 1.0);   // 1 at the top edge, 0 at the bottom
    col += vec3(0.05 * innerBand * fromTop * fromTop * (0.7 + 0.3 * uLift));

    // 1 px anti-aliased edge; the shadow continues outside.
    float cover = 1.0 - smoothstep(-0.75, 0.75, sd);
    float outA = mix(shadow, 1.0, cover) * uAlpha;
    outColor = vec4(col * uAlpha * cover, outA);
}
"""
}
