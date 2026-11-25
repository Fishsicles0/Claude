#version 330 compatibility
/* Evade Shaders - composite1: volumetric light (god rays), rendered at half resolution (scale.composite1).
 * Marches from the camera towards each pixel, testing the shadow map at every step: light that reaches a step
 * scatters towards the eye (Mie-like phase function, Beer-Lambert extinction). Only runs when enabled. */
#include "/lib/settings.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/common.glsl"
#include "/lib/sky.glsl"
#include "/lib/shadows.glsl"
#include "/lib/fog.glsl"

uniform sampler2D depthtex0;

in vec2 uv;

/* RENDERTARGETS: 3 */
layout(location = 0) out vec4 outVL;

void main() {
#if defined SHADOWS && defined VOLUMETRIC_LIGHT
    if (!isOverworld() || isEyeInWater >= 2) { outVL = vec4(0.0); return; }
    float depth = texture(depthtex0, uv).r;
    vec3 viewPos = screenToView(uv, depth);
    vec3 end = viewToPlayer(viewPos) - gbufferModelViewInverse[3].xyz;
    float maxLen = min(shadowDistance, 112.0);
    float len = min(length(end), maxLen);
    vec3 dir = normalize(end);
    vec3 lightDir = lightDirWorld();

    float noise = ignAnimated(gl_FragCoord.xy, frameCounter);
    float stepLen = len / float(VL_SAMPLES);
    bool water = isEyeInWater == 1;
    float sigma = water ? 0.06 : (0.0045 + rainStrength * 0.008) * FOG_DENSITY;
    vec3 origin = gbufferModelViewInverse[3].xyz;
    float lit = 0.0, trans = 1.0;
    for (int i = 0; i < VL_SAMPLES; i++) {
        vec3 p = origin + dir * (stepLen * (float(i) + noise));
        vec3 clip = (shadowProjection * (shadowModelView * vec4(p, 1.0))).xyz;
        vec3 sc = shadowUV(clip);
        float v = texture(shadowtex0, vec3(sc.xy, sc.z - 0.0002));
        float stepT = exp(-sigma * stepLen);
        lit += v * trans * (1.0 - stepT);
        trans *= stepT;
    }
    float c = dot(dir, lightDir);
    float phase = water ? 0.25 + 0.5 * phaseMie(c, 0.5) : 0.05 + phaseMie(c, 0.65) * 0.9;
    vec3 light = directLightColor() * phase * lit * VL_STRENGTH;
    // Fades with the sky light at the eye: shafts in an open cave mouth yes, deep underground no.
    light *= 0.15 + 0.85 * eyeSkyAccess();
    if (water) light *= underwaterFogColor() * 6.0 + 0.02;
    outVL = vec4(light * 1.4, 1.0);
#else
    outVL = vec4(0.0);
#endif
}
