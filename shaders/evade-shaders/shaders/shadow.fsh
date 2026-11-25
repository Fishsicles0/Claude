#version 330 compatibility
/* Evade Shaders - shadow pass fragment shader. shadowcolor0 keeps the colour of translucent casters so
 * stained glass can tint the light (Coloured Shadows option). */
#include "/lib/settings.glsl"

uniform sampler2D gtexture;

in vec2 texUV;
in vec4 vertColor;
flat in int matId;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outShadowColor;

void main() {
    vec4 c = texture(gtexture, texUV) * vertColor;
    if (c.a < 0.1) discard;
    outShadowColor = c;
}
