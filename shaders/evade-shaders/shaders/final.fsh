#version 330 compatibility
/* Evade Shaders - final: bloom, exposure, tonemapping, colour grading, sharpening, vignette, output to sRGB.
 * Bloom reads the mip chain of the HDR image (Iris builds it because of colortex0MipmapEnabled) and sums a few
 * blurred levels - cheap enough for integrated GPUs. */
#include "/lib/settings.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/common.glsl"

#ifdef BLOOM
const bool colortex0MipmapEnabled = true;
#endif

uniform sampler2D colortex0;

in vec2 uv;

layout(location = 0) out vec4 outColor;

// ACES filmic curve, Narkowicz 2015 fit.
vec3 tonemapACES(vec3 x) { return saturate((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)); }
// Soft: Reinhard on luminance with a white point, keeps hues.
vec3 tonemapSoft(vec3 x) {
    float l = luma(x);
    float m = l * (1.0 + l / 16.0) / (1.0 + l);
    return saturate(x * (m / max(l, 1e-5)));
}
// Neutral filmic (Hable 2010 curve).
vec3 hable(vec3 x) { return ((x * (0.15 * x + 0.05) + 0.004) / (x * (0.15 * x + 0.5) + 0.06)) - 0.0667; }
vec3 tonemapFilmic(vec3 x) { return saturate(hable(x * 2.0) / hable(vec3(11.2))); }

vec3 sampleScene(vec2 p) { return texture(colortex0, p).rgb; }

void main() {
    vec2 p = uv;
    if (isEyeInWater == 1) {
#ifdef UNDERWATER_WOBBLE
        p += vec2(sin(p.y * 24.0 + frameTimeCounter * 2.0), cos(p.x * 20.0 + frameTimeCounter * 1.7)) * 0.0012;
#endif
    }
    vec3 color = sampleScene(p);

    if (SHARPENING > 0.0) {   // Light unsharp mask (restores detail TAA softens). Clamped to avoid halos.
        vec2 t = 1.0 / vec2(viewWidth, viewHeight);
        vec3 nb = sampleScene(p + vec2(t.x, 0.0)) + sampleScene(p - vec2(t.x, 0.0))
                + sampleScene(p + vec2(0.0, t.y)) + sampleScene(p - vec2(0.0, t.y));
        vec3 sharp = color + (color - nb * 0.25) * float(SHARPENING);
        color = clamp(sharp, color * 0.5, color * 1.5 + 0.02);
    }

#ifdef BLOOM
    {
        vec3 bloom = vec3(0.0);
        float total = 0.0;
        vec2 t = 1.0 / vec2(viewWidth, viewHeight);
        for (int i = 2; i <= 7; i++) {
            float lod = float(i);
            vec2 o = t * exp2(lod) * 0.5;
            vec3 s = textureLod(colortex0, p + vec2( o.x,  o.y), lod).rgb + textureLod(colortex0, p + vec2(-o.x,  o.y), lod).rgb
                   + textureLod(colortex0, p + vec2( o.x, -o.y), lod).rgb + textureLod(colortex0, p + vec2(-o.x, -o.y), lod).rgb;
            float w = 1.0 / (1.0 + float(i - 2) * 0.6);
            bloom += s * 0.25 * w; total += w;
        }
        bloom /= total;
        // Energy-conserving: a small share of every pixel's light spreads out; very bright lights spread more.
        float amount = 0.045 * BLOOM_STRENGTH * (isEyeInWater == 1 ? 2.5 : 1.0);
        color = mix(color, bloom, amount) + max(bloom - 1.5, 0.0) * 0.04 * BLOOM_STRENGTH;
    }
#endif

    color *= EXPOSURE;
#if TONEMAP == 0
    color = tonemapACES(color);
#elif TONEMAP == 1
    color = tonemapSoft(color);
#else
    color = tonemapFilmic(color);
#endif

    // Grading in display-referred linear space.
    float l = luma(color);
    color = max(mix(vec3(l), color, SATURATION), 0.0);
    color = toGamma(color);
    color = saturate((color - 0.5) * CONTRAST + 0.5);

#ifdef VIGNETTE
    vec2 c = uv - 0.5;
    color *= 1.0 - dot(c, c) * VIGNETTE_STRENGTH * 1.6;
#endif
    color += (ign(gl_FragCoord.xy) - 0.5) / 255.0; // dither away banding
    outColor = vec4(color, 1.0);
}
