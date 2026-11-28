#version 330

// Evade's GUI shape primitive: an analytic signed-distance rounded rectangle (per-corner radii), drawn per pixel in
// device-pixel units, so corners and 1-device-pixel borders stay exact at any GUI scale and any window size.
//
// Modes (ShapeA.w & 3):
//   0 FILL   fill (flat, linear, radial or mirrored gradient), border ring, specular top rim, inner shade, grain
//   1 SHADOW Gaussian falloff of the (offset) shape, optionally knocked out of the shape's own body
//   2 GLOW   the same falloff without knockout (drawn by the additive pipeline)
//   3 ARC    a round-capped ring arc (circular progress)
// Every pixel is dithered by +-0.5 LSB of interleaved gradient noise before the 8-bit write, so gradients, glows
// and scrims never band. Output is premultiplied alpha (the pipelines blend ONE, ONE_MINUS_SRC_ALPHA / ONE, ONE).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 local;
flat in vec4 colA;
flat in vec4 colB;
flat in vec4 colC;
flat in vec4 shapeA;
flat in vec4 shapeR;
flat in vec4 shapeG;
flat in vec4 shapeL;

out vec4 fragColor;

const float TAU = 6.28318530718;

float ign(vec2 px) {
    return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float cornerRadius(vec2 p, vec4 r) {
    return p.x > 0.0 ? (p.y > 0.0 ? r.z : r.y) : (p.y > 0.0 ? r.w : r.x);
}

// Signed distance to a rounded box of half size b and corner radius r (negative inside).
float sdBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// The outward normal of the box's nearest edge (the light model reads its y). Only computed when lit.
vec2 boxNormal(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    vec2 s = vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    if (q.x > 0.0 && q.y > 0.0) return normalize(q) * s;
    return q.x > q.y ? vec2(s.x, 0.0) : vec2(0.0, s.y);
}

// erf(x) for x >= 0 (Abramowitz and Stegun 7.1.26, |error| < 1.5e-7).
float erfPos(float x) {
    float t = 1.0 / (1.0 + 0.3275911 * x);
    float poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t;
    return 1.0 - poly * exp(-x * x);
}

// Coverage of a Gaussian-blurred edge at signed distance d (0.5 on the edge).
float gaussEdge(float d, float sigma) {
    float x = d / (sigma * 1.41421356);
    float e = erfPos(abs(x));
    return x >= 0.0 ? 0.5 - 0.5 * e : 0.5 + 0.5 * e;
}

// A ring arc around the centre: radius ra, thickness th, from angle a0 (0 = top, clockwise) sweeping sw radians.
float sdArc(vec2 p, float ra, float th, float a0, float sw) {
    float ang = atan(p.x, -p.y);
    float rel = mod(ang - a0, TAU);
    if (sw >= TAU - 1e-3 || rel <= sw) return abs(length(p) - ra) - th * 0.5;
    vec2 e0 = ra * vec2(sin(a0), -cos(a0));
    vec2 e1 = ra * vec2(sin(a0 + sw), -cos(a0 + sw));
    return min(length(p - e0), length(p - e1)) - th * 0.5;
}

vec4 premul(vec4 c) {
    return vec4(c.rgb * c.a, c.a);
}

void main() {
    vec2 p = local;
    vec2 hb = shapeA.xy;
    float bw = shapeA.z;
    int flags = int(shapeA.w + 0.5);
    int mode = flags & 3;
    vec4 outc;

    if (mode == 1 || mode == 2) {
        float knock = 1.0;
        if ((flags & 4) != 0) { // knocked-out body: most of a big shadow's pixels end here, before any erf
            float d0 = sdBox(p, hb, cornerRadius(p, shapeR));
            if (d0 < -0.5) discard;
            knock = 1.0 - clamp(0.5 - d0, 0.0, 1.0);
        }
        vec2 ps = p - vec2(0.0, shapeL.w);
        float sigma = max(shapeL.z, 0.05);
        float d = sdBox(ps, hb, cornerRadius(ps, shapeR));
        if (d > 3.2 * sigma) discard;
        float a = gaussEdge(d, sigma) * knock;
        outc = premul(vec4(colA.rgb, colA.a * a));
    } else {
        vec2 n = vec2(0.0, -1.0);
        float d;
        float r = cornerRadius(p, shapeR);
        if (mode == 3) {
            float th = shapeG.z;
            d = sdArc(p, min(hb.x, hb.y) - th * 0.5, th, shapeG.x, shapeG.y);
        } else {
            d = sdBox(p, hb, r);
        }
        float cov = clamp(0.5 - d, 0.0, 1.0);
        if (cov <= 0.0) discard;
        // A bare ring (a stroke or hairline): nothing to draw inside it.
        if (bw > 0.0 && d < -bw - 0.5 && colA.a <= 0.0 && shapeG.w < 0.5 && shapeL.x <= 0.0 && shapeL.y == 0.0) discard;
        if (mode == 0 && (shapeL.x > 0.0 || shapeL.y != 0.0)) n = boxNormal(p, hb, r);

        vec4 fill = premul(colA);
        int kind = mode == 3 ? 0 : int(shapeG.w + 0.5);
        if (kind != 0) {
            float t;
            if (kind == 2) {
                t = length(p - shapeG.xy) / max(shapeG.z, 1e-3);
            } else {
                vec2 g = shapeG.xy;
                float len = max(length(g), 1e-3);
                float along = dot(p, g / len);
                if (kind == 3) {
                    float s = clamp(shapeG.z, 0.0, 0.999);
                    t = (abs(along) / (len * 0.5) - s) / (1.0 - s);
                } else {
                    float e = shapeG.z > 0.0 ? shapeG.z : 1.0;
                    t = (along / len + 0.5) / e;
                }
            }
            fill = mix(fill, premul(colB), clamp(t, 0.0, 1.0));
        }

        if (mode == 0) {
            float depth = -d - bw; // distance inside the border ring
            // Grain: static per-pixel noise that breaks up flat translucent fills.
            if (shapeL.z > 0.0) fill.rgb += (hash12(floor(gl_FragCoord.xy)) - 0.5) * shapeL.z * fill.a;
            // Inner shade: a soft dark band inside the bottom edge (> 0, raised) or the top edge (< 0, sunken).
            if (shapeL.y != 0.0) {
                float facing = shapeL.y > 0.0 ? max(n.y, 0.0) : max(-n.y, 0.0);
                float k = abs(shapeL.y) * (1.0 - smoothstep(0.0, 2.5, depth)) * facing;
                fill = vec4(fill.rgb * (1.0 - k), k + fill.a * (1.0 - k));
            }
            // Specular rim: one device pixel inside the top edge, lit from above, brightest at the highlight x and
            // fading out toward the left and right ends (a light catch, not a frame).
            if (shapeL.x > 0.0) {
                float band = clamp(depth + 0.5, 0.0, 1.0) * clamp(1.5 - depth, 0.0, 1.0);
                float facing = clamp(-n.y, 0.0, 1.0);
                float across = 1.0 - clamp(abs(p.x - shapeL.w) / max(hb.x, 1.0), 0.0, 1.0);
                float k = shapeL.x * band * facing * facing * smoothstep(0.0, 0.45, across);
                fill = vec4(1.0) * k + fill * (1.0 - k);
            }
        }

        outc = fill * cov;
        if (bw > 0.0) {
            float inner = clamp(0.5 - (d + bw), 0.0, 1.0);
            float ring = max(cov - inner, 0.0);
            vec4 bc = premul(colC);
            outc = bc * ring + outc * (1.0 - bc.a * ring);
        }
    }

    // +-0.5 LSB dither, scaled down only on near-transparent anti-aliasing fringes.
    outc.rgb += (ign(gl_FragCoord.xy) - 0.5) / 255.0 * min(1.0, outc.a * 8.0);
    outc.rgb = max(outc.rgb, vec3(0.0));
    if (outc.a <= 0.0005 && max(outc.r, max(outc.g, outc.b)) <= 0.0005) discard;
    fragColor = outc;
}
