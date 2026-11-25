/* Evade Shaders - shadow map helpers.
 *
 * The shadow map is an orthographic depth map from the sun/moon. Its xy is "distorted" so that texels are
 * dense near the player and sparse far away (a single-cascade alternative to cascaded shadow maps):
 *     p' = p / (k*|p| + (1-k))
 * Filtering: SHADOW_FILTER 0 = one hardware 2x2 comparison, 1 = PCF over a rotated Vogel disk,
 *            2 = PCSS (blocker search sets a per-pixel penumbra size, then PCF).
 */
#ifndef EVADE_SHADOWS
#define EVADE_SHADOWS

float distortFactor(vec2 p) { return SHADOW_DISTORT * length(p) + (1.0 - SHADOW_DISTORT); }
vec3 distortShadow(vec3 clip) { return vec3(clip.xy / distortFactor(clip.xy), clip.z * 0.5); }

// World-space size of one shadow texel at a given (undistorted) shadow-clip position.
float shadowTexelWorld(vec2 clipXY) {
    float d = distortFactor(clipXY);
    return (2.0 * shadowDistance / float(shadowMapResolution)) * d * d / (1.0 - SHADOW_DISTORT);
}

#if defined SHADOWS && !defined SHADOW_PASS
uniform sampler2DShadow shadowtex0;   // hardware depth comparison (with bilinear 2x2 filtering)
uniform sampler2D shadowtex1;         // raw depth of opaque casters only
uniform sampler2D shadowcolor0;       // tint of translucent casters

vec2 vogel(int i, int n, float rot) {
    float r = sqrt((float(i) + 0.5) / float(n));
    float a = float(i) * 2.39996323 + rot;
    return r * vec2(cos(a), sin(a));
}

// Shadow-space position in [0,1] from an undistorted shadow-clip position.
vec3 shadowUV(vec3 clip) { return distortShadow(clip) * 0.5 + 0.5; }

/* Visibility of the light (rgb so stained glass can tint it). clip = undistorted shadow clip position.
 * noise in [0,1) rotates the sample pattern per pixel (TAA averages it). */
vec3 sampleShadow(vec3 clip, float noise) {
    vec3 sc = shadowUV(clip);
    if (any(greaterThan(abs(sc.xy - 0.5), vec2(0.499)))) return vec3(1.0);
    float texel = 1.0 / float(shadowMapResolution);
    float distort = distortFactor(clip.xy);
    sc.z -= 0.00012 * distort; // small constant bias; the normal offset in the vertex shader does the rest

    float vis;
#if SHADOW_FILTER == 0
    vis = texture(shadowtex0, sc);
#else
    float rot = noise * TAU;
    // Filter radius chosen in blocks, converted to texels, so softness looks the same near and far.
    float texelWorld = shadowTexelWorld(clip.xy);
    float radius = clamp(0.05 * SHADOW_SOFTNESS / texelWorld, 1.0, 6.0);
  #if SHADOW_FILTER == 2
    // PCSS: average blocker depth in a wide disk, penumbra grows with receiver-blocker distance.
    float blockers = 0.0, blockerDepth = 0.0;
    float searchR = clamp(0.5 * SHADOW_SOFTNESS / texelWorld, 2.0, 16.0) * texel;
    for (int i = 0; i < 6; i++) {
        float d = texture(shadowtex1, sc.xy + vogel(i, 6, rot) * searchR).r;
        if (d < sc.z) { blockers += 1.0; blockerDepth += d; }
    }
    if (blockers < 0.5) {
    #ifndef COLORED_SHADOWS
        return vec3(1.0);  // nothing opaque nearby: fully lit, skip the filter
    #endif
        radius = 1.0;
    } else {
        blockerDepth /= blockers;
        // sc.z is clip z * 0.25 + 0.5 and Iris' shadow clip spans 256 blocks, so one unit of sc.z is ~512 blocks.
        // Penumbra = receiver-blocker distance * light size (the sun's ~0.5 degree disk, widened a little).
        float penumbraBlocks = (sc.z - blockerDepth) * 512.0 * 0.025 * SHADOW_SOFTNESS;
        radius = clamp(penumbraBlocks / texelWorld, 1.0, 14.0);
    }
    const int N = 12;
  #else
    const int N = 9;
  #endif
    vis = 0.0;
    for (int i = 0; i < N; i++) vis += texture(shadowtex0, vec3(sc.xy + vogel(i, N, rot) * radius * texel, sc.z));
    vis /= float(N);
#endif

#ifdef COLORED_SHADOWS
    // If only translucent casters (stained glass, ice) block the light, let it through tinted.
    float solid = step(sc.z, texture(shadowtex1, sc.xy).r);
    if (solid > vis) {
        vec4 tint = texture(shadowcolor0, sc.xy);
        vec3 glass = mix(vec3(1.0), toLinear(tint.rgb), saturate(tint.a * 1.4));
        return vec3(vis) + (solid - vis) * glass;
    }
#endif
    return vec3(vis);
}
#endif

#endif
