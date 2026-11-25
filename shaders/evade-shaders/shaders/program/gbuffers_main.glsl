/* Evade Shaders - shared code for the lit geometry programs (forward lighting).
 * Each gbuffers_*.vsh/.fsh defines VERTEX or FRAGMENT plus one PROG_* switch, then includes this file:
 *   PROG_TERRAIN   opaque/cutout blocks (waving plants, emissive blocks)
 *   PROG_WATER     translucent blocks: water writes surface data for composite.fsh, glass/ice are lit here
 *   PROG_ENTITIES  mobs, players, item frames... (hurt flash)
 *   PROG_HAND      first-person hand and held items
 *   PROG_TEXTURED  particles and other simple textured geometry (lightmap only, no shadow lookups)
 *   PROG_WEATHER   rain and snow
 */
#include "/lib/settings.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/common.glsl"
#include "/lib/sky.glsl"
#include "/lib/shadows.glsl"

#if defined PROG_TEXTURED || defined PROG_WEATHER
    #define NO_SHADOW_LOOKUP
#endif

// =====================================================================================================================
#ifdef VERTEX

in vec4 mc_Entity;
in vec2 mc_midTexCoord;

out vec2 texUV;
out vec2 lmUV;
out vec4 vertColor;
out vec3 playerPos;
out vec3 worldNormal;
out vec3 shadowClip;
flat out int matId;
flat out vec3 directCol;
flat out vec3 ambientCol;

// Sub-pixel camera jitter for TAA (Halton 2,3 sequence, 8 frames).
vec2 taaJitter() {
    const vec2 seq[8] = vec2[8](vec2(0.5, 0.333), vec2(0.25, 0.667), vec2(0.75, 0.111), vec2(0.125, 0.444),
                                vec2(0.625, 0.778), vec2(0.375, 0.222), vec2(0.875, 0.556), vec2(0.0625, 0.889));
    return (seq[frameCounter & 7] - 0.5) / vec2(viewWidth, viewHeight);
}

#if defined PROG_TERRAIN && (defined WAVING_PLANTS || defined WAVING_LEAVES)
// Wind sway in blocks: two slow gust waves across the world plus a faster flutter.
vec3 wind(vec3 worldPos, float amount) {
    float t = frameTimeCounter * 1.7;
    float phase = dot(worldPos.xz, vec2(0.21, 0.33));
    vec2 gust = vec2(sin(t + phase), sin(t * 0.83 + phase * 1.31 + 1.7)) * 0.65;
    vec2 flutter = vec2(sin(t * 2.9 + worldPos.x * 1.73 + worldPos.y), cos(t * 2.4 + worldPos.z * 1.91)) * 0.3;
    float storm = 1.0 + rainStrength * 0.9 + thunderStrength * 0.6;
    vec2 xz = (gust + flutter) * amount * WIND_STRENGTH * storm;
    return vec3(xz.x, (flutter.x * 0.3) * amount * WIND_STRENGTH, xz.y);
}
#endif

void main() {
    texUV = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmUV  = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    vertColor = gl_Color;
    matId = int(mc_Entity.x + 0.5);

    vec3 viewPos = (gl_ModelViewMatrix * gl_Vertex).xyz;
    playerPos = viewToPlayer(viewPos);

#if defined PROG_TERRAIN && (defined WAVING_PLANTS || defined WAVING_LEAVES)
    float sway = 0.0;
    bool topVertex = texUV.y < mc_midTexCoord.y;
  #ifdef WAVING_PLANTS
    if (matId == PLANT_ID && topVertex) sway = 0.09;
    else if (matId == TALL_PLANT_ID) sway = topVertex ? 0.16 : 0.09;
  #endif
  #ifdef WAVING_LEAVES
    if (matId == LEAVES_ID) sway = 0.035;
  #endif
    if (sway > 0.0) {
        // Only sway where the wind can reach (sky light), so plants in caves stay still.
        float open = saturate(lmUV.y * 1.4 - 0.4);
        playerPos += wind(playerPos + cameraPosition, sway * open);
        viewPos = (gbufferModelView * vec4(playerPos, 1.0)).xyz;
    }
#endif

    worldNormal = normalize(mat3(gbufferModelViewInverse) * (gl_NormalMatrix * gl_Normal));
    gl_Position = gl_ProjectionMatrix * vec4(viewPos, 1.0);
#ifdef TAA
    gl_Position.xy += taaJitter() * 2.0 * gl_Position.w;
#endif

    directCol = vec3(0.0); ambientCol = vec3(0.0);
    if (isOverworld()) { directCol = directLightColor(); ambientCol = ambientLightColor(); }

    shadowClip = vec3(0.0);
#if defined SHADOWS && !defined NO_SHADOW_LOOKUP
    if (isOverworld()) {
        vec3 lightDir = lightDirWorld();
        bool plant = matId == PLANT_ID || matId == TALL_PLANT_ID || matId == LEAVES_ID;
        vec3 sc = (shadowProjection * (shadowModelView * vec4(playerPos, 1.0))).xyz;
        // Normal-offset bias: push the lookup off the surface by about one shadow texel (more where texels are big).
        vec3 offsetDir = plant ? lightDir : worldNormal * sign(dot(worldNormal, lightDir) + 1e-3);
        vec3 biased = playerPos + offsetDir * (shadowTexelWorld(sc.xy) * 1.2 + 0.02);
        shadowClip = (shadowProjection * (shadowModelView * vec4(biased, 1.0))).xyz;
    }
#endif
}

#endif
// =====================================================================================================================
#ifdef FRAGMENT

uniform sampler2D gtexture;
uniform vec4 entityColor;

in vec2 texUV;
in vec2 lmUV;
in vec4 vertColor;
in vec3 playerPos;
in vec3 worldNormal;
in vec3 shadowClip;
flat in int matId;
flat in vec3 directCol;
flat in vec3 ambientCol;

#include "/lib/lighting.glsl"
#ifdef PROG_WATER
#include "/lib/waves.glsl"
#endif

#if defined PROG_WATER
/* RENDERTARGETS: 0,1,2 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outSurface; // xy: packed normal, z: sky light, w: 1 water / 0.5 reflective glass / 0
layout(location = 2) out vec4 outWater;   // rgb: water tint, a: sun visibility on the surface
#elif defined PROG_HAND
/* RENDERTARGETS: 0,1 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outSurface; // cleared so composite never treats the hand as water
#else
/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;
#endif

// Light visibility at this fragment; fades into a sky-light based guess past the shadow distance.
vec3 shadowVisibility(float NdotL, bool foliage) {
#if defined SHADOWS && !defined NO_SHADOW_LOOKUP
    if (!isOverworld() || (NdotL <= 0.0 && !foliage)) return vec3(0.0);
    float dist = length(playerPos);
    float fade = smoothstep(shadowDistance * 0.8, shadowDistance * 0.98, dist);
    float skyGuess = pow(saturate((lmUV.y - 0.0625) * 1.07), 6.0);
    if (fade >= 1.0) return vec3(skyGuess);
    vec3 vis = sampleShadow(shadowClip, ignAnimated(gl_FragCoord.xy, frameCounter));
    return mix(vis, vec3(skyGuess), fade);
#else
    // No shadow map: estimate from the sky-light level (open sky = lit, under cover = shade).
    return vec3(pow(saturate((lmUV.y - 0.0625) * 1.07), 6.0));
#endif
}

void main() {
    vec4 albedo = texture(gtexture, texUV) * vertColor;
#if defined PROG_WATER
    if (matId != WATER_ID && albedo.a < 0.01) discard;
#else
    if (albedo.a < 0.1) discard;
#endif
#if defined PROG_ENTITIES || defined PROG_HAND
    albedo.rgb = mix(albedo.rgb, entityColor.rgb, entityColor.a); // red hurt flash, creeper white flash
#endif

    bool foliage = matId == PLANT_ID || matId == TALL_PLANT_ID || matId == LEAVES_ID;
    vec3 n = normalize(worldNormal);
    float NdotL = isOverworld() ? dot(n, lightDirWorld()) : 0.0;
    vec2 lm = clamp(lmUV, 1.0 / 32.0, 31.0 / 32.0);

#ifdef PROG_WEATHER
    // Rain/snow: lit like the sky around it, kept subtle.
    vec3 c = toLinear(albedo.rgb) * (toLinear(texture(lightmap, lm).rgb) * 0.9 + 0.05);
    outColor = vec4(c, albedo.a * 0.55);
    return;
#endif

#ifdef PROG_WATER
    if (matId == WATER_ID) {
        // The water itself is composed in composite.fsh (absorption, refraction, reflections) from this data.
        vec3 wn = n;
    #ifdef WATER_WAVES
        if (n.y > 0.5) wn = waterWaveNormal(playerPos.xz + cameraPosition.xz, frameTimeCounter);
    #endif
        float sunVis = luma(shadowVisibility(1.0, false)) * step(0.0, NdotL);
        outColor = vec4(0.0);
        outSurface = vec4(octEncode(wn), lm.y, 1.0);
        outWater = vec4(vertColor.rgb, sunVis);
        return;
    }
#endif

    vec3 lin = toLinear(albedo.rgb);

    LightInputs li;
    li.lm = lm;
    li.normal = n;
    li.NdotL = NdotL;
    li.foliage = foliage ? 1.0 : 0.0;
    li.shadow = shadowVisibility(NdotL, foliage);
#ifdef WATER_CAUSTICS
    if (isEyeInWater == 1 && isOverworld()) {
        // Rippling bright lines on lit surfaces while the camera is underwater (three crossing sine bands).
        vec3 wp = playerPos + cameraPosition;
        float t = frameTimeCounter * 1.3;
        float c = sin(wp.x * 1.3 + wp.y * 0.4 + t) + sin(wp.z * 1.7 - t * 1.1) + sin((wp.x + wp.z) * 0.9 + t * 0.7);
        li.shadow *= 0.55 + 1.3 * pow(1.0 - abs(c) / 3.0, 5.0);
    }
#endif
    li.directCol = directCol;
    li.ambientCol = ambientCol;
    vec3 color = lin * surfaceLight(li);

#ifdef FOLIAGE_TRANSLUCENCY
    if (foliage && isOverworld()) {
        // Light shining through thin leaves towards the viewer.
        vec3 viewDir = normalize(playerPos);
        float back = pow(saturate(dot(viewDir, lightDirWorld())), 3.0);
        float skyEnergy = saturate(lm.y * 1.07 - 0.07);
        color += lin * directCol * li.shadow * back * 0.9 * skyEnergy * skyEnergy;
    }
#endif

#if defined PROG_TERRAIN && defined EMISSIVE_BLOCKS
    if (matId == EMISSIVE_ID || matId == LAVA_ID) {
        // Only the bright texels glow (the flame of a torch, not its stick). Colour comes from the texture itself.
        float mask = matId == LAVA_ID ? 1.0 : smoothstep(0.45, 0.85, max(albedo.r, max(albedo.g, albedo.b)));
        color += lin * mask * 3.0 * EMISSIVE_STRENGTH;
    }
#endif

#ifdef PROG_WATER
    // Glass, ice, slime, honey...: lit here and blended; glass/ice also flag themselves for reflections.
    float reflective = 0.0;
  #ifdef GLASS_REFLECTIONS
    if (matId == GLASS_ID || matId == ICE_ID) reflective = 0.5;
  #endif
    outColor = vec4(color, albedo.a);
    outSurface = vec4(octEncode(n), lm.y, reflective);
    outWater = vec4(0.0);
#elif defined PROG_HAND
    outColor = vec4(color, albedo.a);
    outSurface = vec4(0.0);
#else
    outColor = vec4(color, albedo.a);
#endif
}

#endif
