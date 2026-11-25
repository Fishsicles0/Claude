/* Evade Shaders - small unlit/emissive programs that don't need the full lighting path:
 *   PROG_BASIC       lines, block outline, leashes (untextured)
 *   PROG_EMISSIVE    beacon beams, spider/enderman eyes (glow)
 *   PROG_GLINT       enchantment glint (additive)
 *   PROG_DAMAGE      block-breaking cracks (multiplicative blend, so the colour stays raw)
 *   PROG_SKYBASIC    the sky dome: we draw the sky ourselves in deferred.fsh, so only black is written here
 *   PROG_SKYTEXTURED the moon (and custom sky textures); the sun is drawn procedurally in deferred.fsh
 */
#include "/lib/settings.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/common.glsl"

#ifdef VERTEX
out vec2 texUV;
out vec4 vertColor;
out vec2 lmUV;
out vec3 playerPos;

vec2 taaJitter() {
    const vec2 seq[8] = vec2[8](vec2(0.5, 0.333), vec2(0.25, 0.667), vec2(0.75, 0.111), vec2(0.125, 0.444),
                                vec2(0.625, 0.778), vec2(0.375, 0.222), vec2(0.875, 0.556), vec2(0.0625, 0.889));
    return (seq[frameCounter & 7] - 0.5) / vec2(viewWidth, viewHeight);
}

void main() {
    texUV = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    lmUV = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    vertColor = gl_Color;
    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;
    playerPos = viewToPlayer(viewPos.xyz);
    gl_Position = gl_ProjectionMatrix * viewPos;
#if defined TAA && !defined PROG_SKYBASIC && !defined PROG_SKYTEXTURED
    gl_Position.xy += taaJitter() * 2.0 * gl_Position.w;
#endif
}
#endif

#ifdef FRAGMENT
uniform sampler2D gtexture;
uniform sampler2D lightmap;
in vec2 texUV;
in vec4 vertColor;
in vec2 lmUV;
in vec3 playerPos;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
#if defined PROG_BASIC
    outColor = vec4(toLinear(vertColor.rgb), vertColor.a);
#elif defined PROG_EMISSIVE
    vec4 c = texture(gtexture, texUV) * vertColor;
    if (c.a < 0.01) discard;
    outColor = vec4(toLinear(c.rgb) * 2.5 * EMISSIVE_STRENGTH, c.a);
#elif defined PROG_GLINT
    vec4 c = texture(gtexture, texUV) * vertColor;
    outColor = vec4(toLinear(c.rgb) * 0.8, c.a);
#elif defined PROG_DAMAGE
    vec4 c = texture(gtexture, texUV) * vertColor;
    if (c.a < 0.01) discard;
    outColor = c;
#elif defined PROG_SKYBASIC
    outColor = vec4(0.0); // sky is drawn in deferred.fsh
#elif defined PROG_SKYTEXTURED
    if (renderStage == MC_RENDER_STAGE_SUN) discard;  // procedural sun instead
    if (!isOverworld()) discard;                          // End sky texture: deferred.fsh draws its own
    vec4 c = texture(gtexture, texUV) * vertColor;
    vec3 col = toLinear(c.rgb);
    if (renderStage == MC_RENDER_STAGE_MOON) col *= vec3(0.9, 0.95, 1.1) * 2.2 * (1.0 - rainStrength);
    outColor = vec4(col * c.a, c.a);
#endif
}
#endif
