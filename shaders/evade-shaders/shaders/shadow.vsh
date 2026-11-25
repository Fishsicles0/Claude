#version 330 compatibility
/* Evade Shaders - shadow pass vertex shader: renders casters from the sun/moon into a distorted shadow map.
 * Water is skipped (it would shadow the sea floor); plants sway exactly as in gbuffers_terrain. */
#define SHADOW_PASS
#include "/lib/settings.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/common.glsl"
#include "/lib/shadows.glsl"

uniform mat4 shadowModelViewInverse;

in vec4 mc_Entity;
in vec2 mc_midTexCoord;

out vec2 texUV;
out vec4 vertColor;
flat out int matId;

#if defined WAVING_PLANTS || defined WAVING_LEAVES
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
    vertColor = gl_Color;
    matId = int(mc_Entity.x + 0.5);
    // Nothing to cast in the Nether/End, and water casts no shadow: send these off-screen (clipped).
    if (!hasSkylight || matId == WATER_ID) { gl_Position = vec4(10.0, 10.0, 10.0, 1.0); return; }

    vec4 shadowView = gl_ModelViewMatrix * gl_Vertex;
#if defined WAVING_PLANTS || defined WAVING_LEAVES
    float sway = 0.0;
    bool topVertex = texUV.y < mc_midTexCoord.y;
    float skyLm = (gl_TextureMatrix[1] * gl_MultiTexCoord1).y;
  #ifdef WAVING_PLANTS
    if (matId == PLANT_ID && topVertex) sway = 0.09;
    else if (matId == TALL_PLANT_ID) sway = topVertex ? 0.16 : 0.09;
  #endif
  #ifdef WAVING_LEAVES
    if (matId == LEAVES_ID) sway = 0.035;
  #endif
    if (sway > 0.0) {
        vec3 playerPos = (shadowModelViewInverse * shadowView).xyz;
        playerPos += wind(playerPos + cameraPosition, sway * saturate(skyLm * 1.4 - 0.4));
        shadowView = shadowModelView * vec4(playerPos, 1.0);
    }
#endif
    vec4 clip = gl_ProjectionMatrix * shadowView;
    gl_Position = vec4(distortShadow(clip.xyz), 1.0);
}
