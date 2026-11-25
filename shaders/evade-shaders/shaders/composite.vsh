#version 330 compatibility
/* Evade Shaders - composite vertex shader: full-screen pass plus the two haze colours used by fog.glsl. */
#include "/lib/settings.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/common.glsl"
#include "/lib/sky.glsl"

out vec2 uv;
flat out vec3 hazeTowardSun, hazeAwayFromSun;

void main() {
    gl_Position = ftransform();
    uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    hazeTowardSun = vec3(0.0); hazeAwayFromSun = vec3(0.0);
    if (isOverworld()) {
        vec3 s = sunDirWorld();
        vec2 h = normalize(s.xz + vec2(1e-4));
        hazeTowardSun   = skyAtmosphere(normalize(vec3(h.x, 0.08, h.y)));
        hazeAwayFromSun = skyAtmosphere(normalize(vec3(-h.x, 0.08, -h.y)));
    }
}
