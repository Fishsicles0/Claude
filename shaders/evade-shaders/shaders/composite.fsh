#version 330 compatibility
/* Evade Shaders - composite: water (absorption, refraction, reflections), reflective glass, then fog.
 * Water was written by gbuffers_water as surface data only (normal, sky light, tint), so it can be composed
 * here with per-channel absorption against the terrain behind it. */
#include "/lib/settings.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/common.glsl"
#include "/lib/sky.glsl"
#define HAZE_FROM_VERTEX
#include "/lib/fog.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex2;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;

in vec2 uv;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

#if WATER_REFLECTIONS == 2
// Screen-space reflection: march the reflected ray across the screen against the opaque depth buffer.
// Linear interpolation of NDC depth in screen space is exact for a perspective projection.
vec4 screenSpaceReflection(vec3 viewPos, vec3 rayDir, float noise) {
    float maxDist = 96.0;
    if (rayDir.z > 0.0) maxDist = min(maxDist, (-near * 1.5 - viewPos.z) / rayDir.z); // stop before the camera
    if (maxDist <= 0.1) return vec4(0.0);
    vec3 start = viewToScreen(viewPos);
    vec3 end = viewToScreen(viewPos + rayDir * maxDist);
    vec3 delta = (end - start) / float(SSR_STEPS);
    vec3 pos = start + delta * (0.15 + noise);
    for (int i = 0; i < SSR_STEPS; i++) {
        if (any(lessThan(pos.xy, vec2(0.0))) || any(greaterThan(pos.xy, vec2(1.0)))) break;
        float sceneDepth = texture(depthtex1, pos.xy).r;
        if (pos.z > sceneDepth && sceneDepth > 0.56) {
            // Refine the hit with a short binary search.
            vec3 lo = pos - delta, hi = pos;
            for (int j = 0; j < 4; j++) {
                vec3 mid = (lo + hi) * 0.5;
                if (mid.z > texture(depthtex1, mid.xy).r) hi = mid; else lo = mid;
            }
            float sd = texture(depthtex1, hi.xy).r;
            float gap = linearDepth(hi.z) - linearDepth(sd);
            if (gap < 1.0 + linearDepth(hi.z) * 0.05 && sd < 1.0) {
                vec2 edge = smoothstep(vec2(0.0), vec2(0.08), hi.xy) * smoothstep(vec2(1.0), vec2(0.92), hi.xy);
                return vec4(texture(colortex0, hi.xy).rgb, edge.x * edge.y);
            }
        }
        pos += delta;
    }
    return vec4(0.0);
}
#endif

void main() {
    vec3 color = texture(colortex0, uv).rgb;
    float depth0 = texture(depthtex0, uv).r;
    vec4 surface = texture(colortex1, uv);
    vec3 viewPos = screenToView(uv, depth0);
    vec3 viewDir = normalize(viewPos);

    // The surface data must belong to the surface actually visible here (same distance), never to an old frame
    // or to something now in front of it (the hand, particles).
    bool sameSurface = depth0 < 1.0 && abs(unpackDistance(surface.z) - length(viewPos)) < 0.05 + 0.01 * length(viewPos);
    bool isWater = sameSurface && surface.a > 0.55;
    bool isGlass = sameSurface && surface.a > 0.2 && surface.a < 0.4;
    float sunVis = saturate((surface.a - 0.6) / 0.4);

    if (isWater || isGlass) {
        vec3 nWorld = octDecode(surface.xy);
        vec3 n = normalize(mat3(gbufferModelView) * nWorld);
        vec4 water = texture(colortex2, uv);
        float skyLevel = saturate((water.a - 1.0 / 32.0) * 16.0 / 15.0);

        if (isWater && isEyeInWater != 1) {
            // ---- what is seen through the water
            vec2 refrUV = uv;
            float depth1 = texture(depthtex1, uv).r;
#ifdef WATER_REFRACTION
            vec3 behindPos = screenToView(uv, depth1);
            float strength = saturate(distance(behindPos, viewPos) * 0.5) * 0.05 / (1.0 + length(viewPos) * 0.08);
            vec2 candidate = uv + n.xy * strength;
            float candDepth = texture(depthtex1, candidate).r;
            if (candDepth > depth0) { refrUV = candidate; depth1 = candDepth; } // only bend to things below the surface
            color = texture(colortex0, refrUV).rgb;
#endif
            vec3 floorPos = screenToView(refrUV, depth1);
            float thickness = depth1 >= 1.0 ? 64.0 : distance(floorPos, viewPos);
            vec3 tint = toLinear(water.rgb);
            vec3 extinction = mix(WATER_EXTINCTION * 1.2, (1.0 - tint) * 0.4 + 0.02, 0.5);
            vec3 T = exp(-extinction * thickness);
            float skyEnergy = skyLevel * skyLevel * (1.0 - rainStrength * 0.5);
            vec3 scatterCol = tint * ambientLightColor() * 0.14 * mix(0.04, 1.0, dayFactor()) * skyEnergy
                            + tint * 0.002;
            color = color * T + scatterCol * (1.0 - T);
        }

        // ---- reflections
        float cosV = saturate(dot(-viewDir, n));
        float f0 = isWater ? 0.02 : 0.04;
        float fresnel = f0 + (1.0 - f0) * pow(1.0 - cosV, 5.0);
        if (isGlass) fresnel *= 0.6;
        if (isWater && isEyeInWater == 1) {
            // Looking up through the surface from below: past the critical angle it mirrors the water.
            float window = smoothstep(0.62, 0.70, cosV);
            color = mix(underwaterFogColor(), color, window);
        } else {
#if WATER_REFLECTIONS >= 1
            vec3 reflView = reflect(viewDir, n);
            vec3 reflWorld = mat3(gbufferModelViewInverse) * reflView;
            vec3 refl = vec3(0.0);
            if (isOverworld()) refl = skyAtmosphere(reflWorld) * skyLevel * skyLevel;
            else if (hasCeiling) refl = toLinear(fogColor) * 0.5;
            else refl = vec3(0.02, 0.012, 0.03);
  #if WATER_REFLECTIONS == 2
            vec4 ssr = screenSpaceReflection(viewPos, reflView, ignAnimated(gl_FragCoord.xy, frameCounter));
            refl = mix(refl, ssr.rgb, ssr.a);
  #endif
            color = mix(color, refl, fresnel);
            // Sun/moon glint (normalised Blinn-Phong), only where the surface itself is in light.
            if (isOverworld() && isWater) {
                vec3 l = lightDirWorld();
                vec3 h = normalize(l - mat3(gbufferModelViewInverse) * viewDir);
                float spec = pow(saturate(dot(nWorld, h)), 900.0) * 140.0;
                color += directLightColor() * spec * sunVis * fresnel * 6.0;
            }
#endif
        }
    }

    color = applyFog(color, viewPos, depth0 >= 1.0);
    outColor = vec4(color, 1.0);
#if DEBUG_VIEW == 3
    outColor = vec4(fogDebug, 1.0);
#elif DEBUG_VIEW == 4
    outColor = vec4(texture(colortex0, uv).rgb, 1.0); // before water and fog
#endif
}
