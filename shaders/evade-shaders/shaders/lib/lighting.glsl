/* Evade Shaders - surface lighting.
 *
 * Fairness: brightness is anchored to the game's own lightmap texture, which already contains the light level,
 * the Brightness slider, night vision, the Darkness effect, lightning and the dimension's ambient light.
 *   - The "floor" (light level 0) and block light come straight from that texture, so a cave is never brighter
 *     than in vanilla. Block light is only re-tinted (same brightness).
 *   - Sky light is re-shaped into sun/moon light (with shadows) + sky ambient, scaled by the energy vanilla
 *     gives that sky-light level, so it vanishes exactly where vanilla's sky light does.
 */
#ifndef EVADE_LIGHTING
#define EVADE_LIGHTING

uniform sampler2D lightmap;

// Warm torch colour: whiter close to the source, deeper orange as it fades. Luminance ~1.
vec3 blockLightTint(float level) {
    vec3 c = mix(vec3(1.0, 0.55, 0.26), vec3(1.0, 0.80, 0.58), level * level);
    return c / luma(c);
}

struct LightInputs {
    vec2  lm;          // lightmap coordinates exactly as the game passes them (block, sky)
    vec3  normal;      // world-space normal
    float NdotL;       // dot(normal, light direction), may be negative
    vec3  shadow;      // light visibility from the shadow map (1 if no shadows)
    float foliage;     // 1 for plants/leaves: softer wrap lighting
    vec3  directCol;   // directLightColor()
    vec3  ambientCol;  // ambientLightColor()
};

vec3 surfaceLight(LightInputs li) {
    const float LM0 = 1.0 / 32.0;
    vec3 floorL = toLinear(texture(lightmap, vec2(LM0, LM0)).rgb);
    vec3 blockL = toLinear(texture(lightmap, vec2(li.lm.x, LM0)).rgb);
    vec3 skyL   = toLinear(texture(lightmap, vec2(LM0, li.lm.y)).rgb);

    vec3 blockOnly = max(blockL - floorL, 0.0);
#ifdef WARM_BLOCKLIGHT
    float blockLevel = saturate((li.lm.x - LM0) * 16.0 / 15.0);
    blockOnly = luma(blockOnly) * blockLightTint(blockLevel);
#endif
    vec3 light = floorL + blockOnly;

    if (isOverworld()) {
        float skyEnergy = max(luma(skyL) - luma(floorL), 0.0);
        float ndl = li.foliage > 0.5 ? 0.35 + 0.65 * abs(li.NdotL) : max(li.NdotL, 0.0);
        vec3 direct = li.directCol * ndl * li.shadow * 1.35;
        float upness = li.foliage > 0.5 ? 0.6 : li.normal.y;
        vec3 ambient = li.ambientCol * (0.42 + 0.18 * upness + 0.04 * li.normal.x);
        light += skyEnergy * (direct + ambient);
    } else {
        light += max(skyL - floorL, 0.0); // Nether/End: keep vanilla exactly
    }
    return light;
}

#endif
