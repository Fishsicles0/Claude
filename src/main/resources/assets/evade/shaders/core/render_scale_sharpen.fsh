#version 330

// Evade Render Scale: scales the smaller world image up to the window with a light contrast-adaptive sharpen.
// The four neighbours are one source texel away, so the sharpening follows the source pixels. Where the local
// contrast is already high (edges), it backs off, so there are no bright halos.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

// 0 = gentle, 1 = strongest. Kept light: it should win back detail, not look processed.
const float SHARPNESS = 0.35;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(InSampler, 0));
    vec4 center = texture(InSampler, texCoord);
    vec3 c = center.rgb;
    vec3 n = texture(InSampler, texCoord + vec2(0.0, -texel.y)).rgb;
    vec3 s = texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb;
    vec3 w = texture(InSampler, texCoord + vec2(-texel.x, 0.0)).rgb;
    vec3 e = texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb;

    vec3 mn = min(c, min(min(n, s), min(w, e)));
    vec3 mx = max(c, max(max(n, s), max(w, e)));
    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(1.0 / 255.0)), 0.0, 1.0));
    vec3 weight = amp * -mix(0.125, 0.2, SHARPNESS);

    vec3 result = (c + (n + s + w + e) * weight) / (1.0 + 4.0 * weight);
    fragColor = vec4(clamp(result, 0.0, 1.0), center.a);
}
