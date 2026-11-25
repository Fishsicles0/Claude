#version 330 compatibility
/* Evade Shaders - composite3: temporal anti-aliasing.
 * The scene is rendered with a sub-pixel jitter each frame; here the previous result is re-projected with the
 * last frame's camera, clamped to the colour range of the current 3x3 neighbourhood (in YCoCg, to reject
 * ghosts) and blended in. Blending uses luminance-weighted colours so bright pixels don't flicker. */
#include "/lib/settings.glsl"
#include "/lib/uniforms.glsl"
#include "/lib/common.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex5;
uniform sampler2D depthtex0;

in vec2 uv;

/* RENDERTARGETS: 0,5 */
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outHistory;

vec3 toYCoCg(vec3 c) { return vec3(0.25 * c.r + 0.5 * c.g + 0.25 * c.b, 0.5 * c.r - 0.5 * c.b, -0.25 * c.r + 0.5 * c.g - 0.25 * c.b); }
vec3 fromYCoCg(vec3 c) { return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z); }
vec3 compress(vec3 c) { return c / (1.0 + luma(c)); }
vec3 expand(vec3 c) { return c / max(1.0 - luma(c), 1e-3); }

void main() {
    vec2 texel = 1.0 / vec2(viewWidth, viewHeight);
    vec3 current = texture(colortex0, uv).rgb;
#ifdef TAA
    float depth = texture(depthtex0, uv).r;
    vec2 prevUV = uv;
    if (depth > 0.56) { // the hand (depth < 0.56) moves with the camera: no re-projection
        vec3 p = viewToPlayer(screenToView(uv, depth));
        if (depth >= 1.0) p = mat3(gbufferModelViewInverse) * screenToView(uv, depth); // sky: rotation only
        else p += cameraPosition - previousCameraPosition;
        vec4 prev = gbufferPreviousProjection * (gbufferPreviousModelView * vec4(p, 1.0));
        prevUV = prev.xy / prev.w * 0.5 + 0.5;
    }

    vec3 cmin = vec3(1e9), cmax = vec3(-1e9), cavg = vec3(0.0);
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        vec3 s = toYCoCg(compress(texture(colortex0, uv + vec2(x, y) * texel).rgb));
        cmin = min(cmin, s); cmax = max(cmax, s); cavg += s;
    }
    vec3 history = toYCoCg(compress(texture(colortex5, prevUV).rgb));
    history = clamp(history, cmin, cmax);

    bool offscreen = any(lessThan(prevUV, vec2(0.0))) || any(greaterThan(prevUV, vec2(1.0)));
    vec2 motionPx = (uv - prevUV) * vec2(viewWidth, viewHeight);
    float weight = offscreen ? 0.0 : mix(0.9, 0.75, saturate(length(motionPx) / 12.0));
    vec3 cur = toYCoCg(compress(current));
    vec3 result = expand(fromYCoCg(mix(cur, history, weight)));
    outColor = vec4(result, 1.0);
    outHistory = vec4(result, 1.0);
#else
    outColor = vec4(current, 1.0);
    outHistory = vec4(current, 1.0);
#endif
}
