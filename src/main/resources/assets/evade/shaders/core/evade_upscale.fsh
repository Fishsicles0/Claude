#version 330

// Evade Upscale: Evade's own edge-aware spatial upscaler for Render Scale, written for Evade.
//
// 1. Read the 4x4 source texels around the output pixel and estimate the edge direction from the luma gradient
//    of the centre 2x2, weighted toward where the pixel falls between them.
// 2. Filter with a windowed-sinc (Lanczos, radius 2) whose footprint is stretched along the edge and squeezed
//    across it, in proportion to how strong the edge is: stair-steps along an edge are smoothed while the edge
//    itself stays crisp. Flat areas get the plain round filter.
// 3. Clamp to the colour range of the centre 2x2, so the negative lobes can't ring.
// 4. Contrast-adaptive sharpen: push the result away from the local average, less where the neighbourhood already
//    spans a wide range (edges), so detail comes back without halos.
//
// SHARPEN_PCT is set by the pipeline: the sharpening strength in percent (0 = none).

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

#ifndef SHARPEN_PCT
#define SHARPEN_PCT 0
#endif

const float PI = 3.14159265;

vec3 tap(ivec2 p, ivec2 size) {
    return texelFetch(InSampler, clamp(p, ivec2(0), size - 1), 0).rgb;
}

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

// Lanczos window over a squared distance in source texels (zero from 2 texels out).
float lanczos2(float d2) {
    if (d2 >= 4.0) return 0.0;
    float d = sqrt(d2);
    if (d < 1.0e-3) return 1.0;
    float a = PI * d;
    float b = 0.5 * a;
    return (sin(a) / a) * (sin(b) / b);
}

void main() {
    ivec2 size = textureSize(InSampler, 0);
    vec2 pos = texCoord * vec2(size) - 0.5;
    vec2 cell = floor(pos);
    vec2 f = pos - cell;
    ivec2 base = ivec2(cell);

    vec3 c[16];
    float l[16];
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec3 s = tap(base + ivec2(i - 1, j - 1), size);
            c[j * 4 + i] = s;
            l[j * 4 + i] = luma(s);
        }
    }

    // Luma gradient at the four centre texels (indices 5, 6, 9, 10), blended bilinearly.
    vec2 g00 = vec2(l[6] - l[4], l[9] - l[1]);
    vec2 g10 = vec2(l[7] - l[5], l[10] - l[2]);
    vec2 g01 = vec2(l[10] - l[8], l[13] - l[5]);
    vec2 g11 = vec2(l[11] - l[9], l[14] - l[6]);
    vec2 g = mix(mix(g00, g10, f.x), mix(g01, g11, f.x), f.y);
    float glen = length(g);
    vec2 across = glen > 1.0e-5 ? g / glen : vec2(1.0, 0.0);
    vec2 along = vec2(-across.y, across.x);
    // 0 on flat areas, 1 on a clear edge.
    float edge = smoothstep(0.03, 0.30, glen);
    float squeeze = 1.0 + 0.9 * edge;     // narrower across the edge
    float stretch = 1.0 / (1.0 + 0.9 * edge); // wider along it

    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 o = vec2(float(i - 1), float(j - 1)) - f;
            float a = dot(o, along), x = dot(o, across);
            float w = lanczos2(a * a * stretch + x * x * squeeze);
            sum += c[j * 4 + i] * w;
            wsum += w;
        }
    }
    vec3 up = sum / max(wsum, 1.0e-4);

    vec3 mn = min(min(c[5], c[6]), min(c[9], c[10]));
    vec3 mx = max(max(c[5], c[6]), max(max(c[9], c[10]), mn));
    up = clamp(up, mn, mx);

#if SHARPEN_PCT > 0
    // Wider ring for the contrast estimate: the cross around the centre 2x2.
    vec3 rmn = min(mn, min(min(c[1], c[2]), min(c[4], c[8])));
    rmn = min(rmn, min(min(c[7], c[11]), min(c[13], c[14])));
    vec3 rmx = max(mx, max(max(c[1], c[2]), max(c[4], c[8])));
    rmx = max(rmx, max(max(c[7], c[11]), max(c[13], c[14])));
    vec3 amp = sqrt(clamp(min(rmn, 1.0 - rmx) / max(rmx, vec3(1.0 / 255.0)), 0.0, 1.0));
    vec3 avg = (c[5] + c[6] + c[9] + c[10]) * 0.25;
    vec3 sharp = up + (up - avg) * amp * (float(SHARPEN_PCT) / 100.0);
    up = clamp(sharp, rmn, rmx);
#endif

    float alpha = texelFetch(InSampler, clamp(ivec2(floor(pos + 0.5)), ivec2(0), size - 1), 0).a;
    fragColor = vec4(clamp(up, 0.0, 1.0), alpha);
}
