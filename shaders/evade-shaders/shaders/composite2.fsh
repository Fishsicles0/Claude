#version 330 compatibility
/* Evade Shaders - composite2: adds the half-resolution volumetric light onto the scene. */
#include "/lib/settings.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex3;

in vec2 uv;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
    vec3 color = texture(colortex0, uv).rgb;
    color += texture(colortex3, uv * 0.5).rgb; // composite1 rendered into the lower-left quarter
    outColor = vec4(color, 1.0);
}
