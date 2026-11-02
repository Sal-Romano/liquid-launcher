package dev.sal.glass.ui.glass

/**
 * GLSL ES 3.00 for the glass. Runs on the TV's Mali-G52 (GLES 3.2) and the emulator (GLES 3.0).
 *
 * Model, after m2-md's screen-space refraction reference (MIT) and TiHxdvt's liquid-glass-webgl
 * (MIT): the panel is a slab with a quarter-circle bevel of width uBevel around its edge. The
 * bevel's height profile gives a surface normal; refract() with IOR 1.52 bends the view ray and
 * the exit point decides which backdrop pixel shows. Two backdrops: the sharp photo, sampled in
 * the bevel ring where the lens bends it, and a deep pre-rendered blur for the interior, which is
 * what a thick frosted slab does to what is behind it. Dispersion samples three wavelengths at
 * the rim; Fresnel (Schlick) brightens the rim where the surface turns away; a directional
 * specular adds the polished edge. Both backdrops are given for the outgoing and incoming photo
 * so the glass crossfades with the wallpaper instead of snapping.
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
uniform sampler2D uSharpA;  // outgoing photo (screen UVs 0..1)
uniform sampler2D uDeepA;   // its deep blur
uniform sampler2D uSharpB;  // incoming photo
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
uniform float uFrostMix;    // 0 = clear glass, 1 = fully frosted interior
uniform float uDark;        // 0 = white frost tint, 1 = smoked tint (bright backdrops)
in vec2 vFrag;
out vec4 outColor;

const float IOR = 1.52;
const vec3  LIGHT = normalize(vec3(-0.35, -0.75, 0.56));   // upper-left, like iOS

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
vec3 sharpAt(vec2 px) {
    vec2 uv = photoUv(px);
    return mix(texture(uSharpA, uv).rgb, texture(uSharpB, uv).rgb, uMix);
}
vec3 deepAt(vec2 px) {
    vec2 uv = photoUv(px);
    return mix(texture(uDeepA, uv).rgb, texture(uDeepB, uv).rgb, uMix);
}

void main() {
    vec2 p = vFrag - uRect.xy;
    float r = min(uRadius, min(uRect.z, uRect.w) - 1.0);
    float sd = sdRoundedBox(p, uRect.zw, r);

    // Shadow outside the slab: soft, offset down, deeper when lifted.
    float shadowD = sdRoundedBox(p - vec2(0.0, 6.0 + 10.0 * uLift), uRect.zw, r);
    float shadow = (1.0 - smoothstep(0.0, 18.0 + 14.0 * uLift, shadowD)) * (0.26 + 0.22 * uLift);
    if (sd > 0.75) {
        outColor = vec4(0.0, 0.0, 0.0, shadow * uAlpha);
        return;
    }

    vec3 n = glassNormal(p, uRect.zw, r, uBevel, uThick);
    float eta = 1.0 / IOR;
    float depth = uThick * (1.0 + 0.6 * uLift);
    float ring = 1.0 - glassHeight(sd, uBevel);               // 1 at the edge, 0 inside

    // Lens: the bevel bends the sharp photo, with dispersion. Blue bends most.
    vec2 offR = refractOffset(n, 1.0 / (IOR - 0.012), depth);
    vec2 offG = refractOffset(n, eta, depth);
    vec2 offB = refractOffset(n, 1.0 / (IOR + 0.012), depth);
    vec3 lens = vec3(sharpAt(vFrag + offR).r, sharpAt(vFrag + offG).g, sharpAt(vFrag + offB).b);

    // Interior: the deep blur, nudged by the same refraction so the frost also bends at the rim.
    vec3 frost = deepAt(vFrag + offG * 0.6);

    // The lens shows at the rim, the frost fills the middle; uFrostMix sets how deep the frost goes.
    float frostAmt = uFrostMix * (1.0 - ring * ring);
    vec3 back = mix(lens, frost, frostAmt);

    // Material: milky white over dark scenes, smoked over bright ones, stronger in the middle.
    vec3 tint = mix(vec3(1.0), vec3(0.08, 0.09, 0.12), uDark);
    float tintAmt = mix(0.30, 0.36, uDark) * (1.0 - ring * 0.7);
    vec3 col = mix(back, tint, tintAmt);
    // Lift the interior's contrast slightly so it reads as a surface, not a dim photo.
    col = mix(vec3(dot(col, vec3(0.299, 0.587, 0.114))), col, 1.08);

    // Fresnel rim and a directional specular along the bevel.
    float cosT = clamp(n.z, 0.0, 1.0);
    float f = fresnel(cosT, 0.04);
    float spec = pow(max(dot(reflect(-LIGHT, n), vec3(0.0, 0.0, 1.0)), 0.0), 48.0);
    float rimLight = (f * 2.4 + spec * (1.0 + 0.9 * uLift)) * (0.5 + 0.5 * ring);
    col += vec3(rimLight);

    // Inner highlight: light enters from the top-left of the slab.
    vec2 g = (p + uRect.zw) / (2.0 * uRect.zw);
    col += vec3(0.12 * (1.0 - clamp(g.x * 0.6 + g.y * 0.9, 0.0, 1.0)));
    // The slab's far edge: a soft band just inside the bevel, lit from above so it is brightest
    // along the top and fades out down the sides. Wide and faint, so it reads as light in glass,
    // not as a drawn border.
    float innerBand = 1.0 - smoothstep(0.0, uBevel * 0.9, abs(-sd - uBevel * 0.7));
    float fromTop = clamp(0.5 - p.y / (2.0 * uRect.w), 0.0, 1.0);   // 1 at the top edge, 0 at the bottom
    col += vec3(0.07 * innerBand * fromTop * fromTop * (0.7 + 0.3 * uLift));

    // 1 px anti-aliased edge; the shadow continues outside.
    float cover = 1.0 - smoothstep(-0.75, 0.75, sd);
    float outA = mix(shadow, 1.0, cover) * uAlpha;
    outColor = vec4(col * uAlpha * cover, outA);
}
"""
}
