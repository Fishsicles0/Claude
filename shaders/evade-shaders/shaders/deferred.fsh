#version 330 compatibility
/* Evade Shaders - deferred: draws the sky (atmosphere, sun, stars, clouds) on every pixel no opaque geometry
 * covered. The moon (gbuffers_skytextured) is already in colortex0 and is added on top.
 *
 * Buffer setup (read by Iris):
 * const int colortex0Format = RGBA16F;   // HDR scene colour
 * const int colortex1Format = RGBA16;    // translucent surface data: packed normal, sky light, material flag
 * const int colortex2Format = RGBA8;     // water tint + sun visibility on the water surface
 * const int colortex3Format = RGBA16F;   // volumetric light (half resolution)
 * const int colortex5Format = RGBA16F;   // TAA history
 * const bool colortex5Clear = false;
 * const vec4 colortex0ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
 * const vec4 colortex1ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
 * const float eyeBrightnessHalflife = 4.0;
 */
#include "/lib/settings.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/common.glsl"
#include "/lib/sky.glsl"
#include "/lib/clouds.glsl"

uniform sampler2D colortex0;
uniform sampler2D depthtex0;

in vec2 uv;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
    vec4 scene = texture(colortex0, uv);
    float depth = texture(depthtex0, uv).r;
    if (depth < 1.0) { outColor = scene; return; }

    vec3 dir = normalize(mat3(gbufferModelViewInverse) * screenToView(uv, 1.0));
    vec3 sky;
    if (hasSkylight) {
        sky = skyAtmosphere(dir) + sunDisk(dir) + scene.rgb; // scene.rgb = moon
#ifdef STARS
        sky += starField(dir);
#endif
#if CLOUD_MODE >= 2
        vec4 clouds = drawClouds(dir, cameraPosition);
        sky = sky * clouds.a + clouds.rgb;
#endif
    } else if (hasCeiling) {
        sky = netherSky(dir);
    } else {
        sky = endSky(dir);
    }
    outColor = vec4(sky, 1.0);
}
