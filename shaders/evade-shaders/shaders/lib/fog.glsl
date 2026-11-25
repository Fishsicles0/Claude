/* Evade Shaders - fog. Atmospheric haze, the render-distance border, underwater/lava/powder snow fog and the
 * game's own blindness/darkness fog (kept at least as strong as vanilla's, so these effects stay fair). */
#ifndef EVADE_FOG
#define EVADE_FOG

vec3 underwaterFogColor() {
    // fogColor is the game's biome water fog colour while underwater; dim it with time of day and depth.
    float light = mix(0.04, 1.0, eyeSkyAccess() * mix(0.12, 1.0, dayFactor()));
    return toLinear(fogColor) * 0.35 * light;
}
const vec3 WATER_EXTINCTION = vec3(0.16, 0.065, 0.05);

vec3 applyFog(vec3 color, vec3 viewPos, bool isSky) {
    vec3 worldDir = normalize(mat3(gbufferModelViewInverse) * viewPos);
    float dist = isSky ? far * 2.0 : length(viewPos);

    if (isEyeInWater == 1) {
        vec3 T = exp(-WATER_EXTINCTION * dist * (0.8 + FOG_DENSITY * 0.2));
        color = color * T + underwaterFogColor() * (1.0 - T);
    } else if (isEyeInWater >= 2) {
        // Lava / powder snow: the game's own short fog.
        float f = saturate((dist - fogStart) / max(fogEnd - fogStart, 0.01));
        color = mix(color, toLinear(fogColor), f);
    } else if (isOverworld()) {
        if (!isSky) {
            vec3 worldPos = viewToPlayer(viewPos) + cameraPosition;
            float heightFalloff = exp(-max(worldPos.y - 62.0, 0.0) / 48.0);
            float density = 0.0011 * FOG_DENSITY * (1.0 + rainStrength * 4.0) * heightFalloff;
            float f = 1.0 - exp(-dist * density);
            // In caves the haze takes the colour of the dark, not the sky.
            vec3 fogCol = skyAtmosphere(vec3(worldDir.x, max(worldDir.y, 0.05), worldDir.z)) * eyeSkyAccess();
            color = mix(color, fogCol, f);
#ifdef BORDER_FOG
            float edge = smoothstep(far * 0.72, far * 0.98, length(viewToPlayer(viewPos).xz));
            color = mix(color, skyAtmosphere(worldDir), edge);
#endif
        }
    } else if (hasCeiling) {
        vec3 fogCol = toLinear(fogColor) * 0.9;
#ifdef NETHER_HAZE
        float f = 1.0 - exp(-dist * 0.010 * FOG_DENSITY);
#else
        float f = 0.0;
#endif
        f = max(f, smoothstep(far * 0.6, far * 0.98, dist));
        color = mix(color, fogCol, f);
    } else {
        vec3 fogCol = vec3(0.016, 0.010, 0.024);
        float f = isSky ? 0.0 : max(1.0 - exp(-dist * 0.0035 * FOG_DENSITY), smoothstep(far * 0.7, far, dist));
        color = mix(color, fogCol, f);
    }

    // Blindness and the Warden's Darkness: never see further than vanilla allows.
    float effect = max(blindness, darknessFactor);
    if (effect > 0.0) {
        float f = saturate((dist - fogStart) / max(fogEnd - fogStart, 0.01));
        color = mix(color, vec3(0.0), f * saturate(effect * 4.0));
    }
    return color;
}

#endif
