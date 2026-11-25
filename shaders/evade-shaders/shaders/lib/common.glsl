/* Evade Shaders - small helpers used everywhere. */
#ifndef EVADE_COMMON
#define EVADE_COMMON

#define PI 3.14159265
#define TAU 6.28318531
#define saturate(x) clamp(x, 0.0, 1.0)

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// The game's colours are sRGB; lighting is done in linear light and converted back in final.
vec3 toLinear(vec3 c) { return c * c * (c * 0.2848 + 0.7152) ; } // cheap fit of pow(c, 2.2) (max error < 1%)
vec3 toGamma(vec3 c)  { return pow(max(c, 0.0), vec3(1.0 / 2.2)); }

// Interleaved gradient noise (Jimenez 2014): cheap per-pixel dither that TAA averages out nicely.
float ign(vec2 pixel) { return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715)))); }
float ignAnimated(vec2 pixel, int frame) { return ign(pixel + 5.588238 * float(frame & 63)); }

float hash12(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

// Octahedral normal packing into two [0,1] channels.
vec2 octEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 e = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    return e * 0.5 + 0.5;
}
vec3 octDecode(vec2 e) {
    e = e * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    float t = saturate(-n.z);
    n.xy += vec2(n.x >= 0.0 ? -t : t, n.y >= 0.0 ? -t : t);
    return normalize(n);
}

vec3 projectAndDivide(mat4 m, vec3 p) { vec4 h = m * vec4(p, 1.0); return h.xyz / h.w; }

// Screen uv + depth-buffer value -> view space position.
vec3 screenToView(vec2 uv, float depth) {
    return projectAndDivide(gbufferProjectionInverse, vec3(uv, depth) * 2.0 - 1.0);
}
vec3 viewToScreen(vec3 v) { return projectAndDivide(gbufferProjection, v) * 0.5 + 0.5; }

vec3 viewToPlayer(vec3 v) { return mat3(gbufferModelViewInverse) * v + gbufferModelViewInverse[3].xyz; }

float linearDepth(float d) { return (2.0 * near * far) / (far + near - (d * 2.0 - 1.0) * (far - near)); }

// Light-direction helpers (world space, unit length).
vec3 sunDirWorld()   { return normalize(mat3(gbufferModelViewInverse) * sunPosition); }
vec3 lightDirWorld() { return normalize(mat3(gbufferModelViewInverse) * shadowLightPosition); }

// 0 at night, 1 in daylight; smooth through dawn and dusk.
float dayFactor() { return smoothstep(-0.08, 0.12, sunDirWorld().y); }

// How much of the sky the camera can see (from the game's own sky-light level at the eye): 0 deep in caves.
float eyeSkyAccess() { return saturate(float(eyeBrightnessSmooth.y) / 240.0); }

#endif
