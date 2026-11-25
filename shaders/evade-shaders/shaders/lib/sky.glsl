/* Evade Shaders - sky model.
 *
 * SKY_MODEL 1: an analytic single-scattering approximation of a Rayleigh + Mie atmosphere.
 *   For a view direction we estimate the optical depth with the Kasten & Young (1989) air-mass formula,
 *   attenuate sunlight by the air mass along the sun direction (this produces the orange/red sunrise and
 *   sunset), and integrate in-scattering of a homogeneous slab: L = E * T_sun * (b_R P_R + b_M P_M) * (1-e^-t)/t.
 *   A little extra blue "multiple scattering" fill keeps the sky from going black at dusk.
 * SKY_MODEL 0: a cheap gradient between the game's own sky and fog colours (POTATO / LOW).
 *
 * All values are linear HDR; final.fsh tonemaps them.
 * Requires uniforms.glsl and common.glsl.
 */
#ifndef EVADE_SKY
#define EVADE_SKY

const vec3  BETA_R = vec3(5.8e-6, 13.5e-6, 33.1e-6); // Rayleigh scattering coefficients at sea level (1/m)
const float BETA_M = 1.2e-5;                          // Mie scattering coefficient (1/m)
const float H_R = 8000.0, H_M = 1200.0;               // scale heights (m)
const float SUN_E = 30.0;                             // sun irradiance in scene units

// Relative optical air mass for a ray with zenith cosine mu (Kasten & Young 1989). 1 at zenith, ~38 at horizon.
float airMass(float mu) {
    mu = clamp(mu, 0.0, 1.0); // below the horizon: treat as grazing (the caller fades the body out)
    float zenithDeg = degrees(acos(mu));
    return 1.0 / (mu + 0.50572 * pow(96.07995 - zenithDeg, -1.6364));
}

// Optical depth (Rayleigh + Mie extinction) along a path of the given air mass.
vec3 opticalDepth(float m) { return (BETA_R * H_R + BETA_M * 1.11 * H_M) * m; }

// Transmittance of sunlight from a body at zenith cosine mu down to the ground.
vec3 bodyTransmittance(float mu) { return exp(-opticalDepth(airMass(mu))); }

float phaseRayleigh(float c) { return 0.0596831 * (1.0 + c * c); }
// Cornette-Shanks phase function, g ~ 0.76 for hazy air.
float phaseMie(float c, float g) {
    float g2 = g * g;
    return 0.1193662 * (1.0 - g2) * (1.0 + c * c) / ((2.0 + g2) * pow(max(1.0 + g2 - 2.0 * g * c, 1e-4), 1.5));
}

// In-scattered light for one celestial body (sun or moon) of irradiance e.
vec3 scatterFrom(vec3 dir, vec3 bodyDir, float e) {
    float muV = max(dir.y, 0.0);
    float m = airMass(muV);
    vec3 sR = BETA_R * H_R * m;
    float sM = BETA_M * H_M * m;
    vec3 ext = sR + sM * 1.11;
    float c = dot(dir, bodyDir);
    vec3 inscatter = (sR * phaseRayleigh(c) + sM * phaseMie(c, 0.76)) * (1.0 - exp(-ext)) / max(ext, vec3(1e-4));
    vec3 sunT = bodyTransmittance(bodyDir.y);
    // Fade the body's contribution as it sinks below the horizon (twilight lasts a little while).
    float above = smoothstep(-0.16, 0.02, bodyDir.y);
    // Multiple-scattering fill: a soft blue term that follows the body's elevation.
    vec3 fill = vec3(0.10, 0.20, 0.42) * 0.012 * smoothstep(-0.2, 0.3, bodyDir.y);
    return e * (sunT * inscatter * above + fill);
}

// Darker, greyer sky in rain and thunder (applied to the sky and to sky-coloured fog).
vec3 weatherTint(vec3 c) {
    float storm = rainStrength * 0.75 + thunderStrength * 0.2;
    vec3 grey = vec3(luma(c)) * vec3(0.92, 0.96, 1.0);
    return mix(c, grey * 0.45, storm);
}

// Sky radiance in a world-space direction, without the sun disk. Overworld only.
vec3 skyAtmosphere(vec3 dir) {
    vec3 sunDir = sunDirWorld();
#if SKY_MODEL == 1
    vec3 d = vec3(dir.x, max(dir.y, 0.0) * 0.98 + 0.02, dir.z);
    d = normalize(d);
    vec3 sky = scatterFrom(d, sunDir, SUN_E);
    sky += scatterFrom(d, -sunDir, SUN_E * 0.0025) * vec3(0.75, 0.85, 1.0); // moonlight (the moon is opposite the sun)
    sky += vec3(0.0018, 0.0026, 0.0055); // airglow + light pollution so night isn't pure black
    // Below the horizon: fade towards a dim ground bounce colour.
    float below = smoothstep(0.0, -0.25, dir.y);
    sky = mix(sky, sky * vec3(0.35, 0.33, 0.30), below);
#else
    vec3 top = toLinear(skyColor);
    vec3 horizon = toLinear(fogColor);
    vec3 sky = mix(horizon, top, smoothstep(-0.05, 0.45, dir.y)) * 1.2;
    float c = dot(dir, sunDir);
    sky += toLinear(fogColor) * pow(saturate(c), 6.0) * 0.6 * smoothstep(-0.1, 0.1, sunDir.y);
#endif
    return weatherTint(sky);
}

// The sun disk with a soft limb (added on top of the sky in deferred.fsh).
vec3 sunDisk(vec3 dir) {
    vec3 sunDir = sunDirWorld();
    float c = dot(dir, sunDir);
    float disk = smoothstep(0.99955, 0.99975, c);
    vec3 col = bodyTransmittance(sunDir.y) * SUN_E * 25.0 * disk;
    return col * (1.0 - rainStrength) * smoothstep(-0.02, 0.0, dir.y);
}

// Colour and strength of the direct light that casts shadows (the sun by day, the moon by night).
// Its brightness is later scaled by the game's own sky-light value, so this only sets hue and sun/moon balance.
vec3 directLightColor() {
    vec3 sunDir = sunDirWorld();
    vec3 sunCol = bodyTransmittance(max(sunDir.y, 0.0)) / bodyTransmittance(1.0).g;
    sunCol *= smoothstep(-0.03, 0.10, sunDir.y);
    vec3 moonCol = vec3(0.55, 0.68, 1.0) * 0.55 * smoothstep(-0.03, 0.10, -sunDir.y);
    return (sunCol + moonCol) * (1.0 - rainStrength * 0.85) * SUNLIGHT_STRENGTH;
}

// Colour of the light from the whole sky dome (normalised; scaled by the game's sky light later).
vec3 ambientLightColor() {
    float day = dayFactor();
    vec3 dayAmb = vec3(0.60, 0.78, 1.0);
    vec3 duskAmb = vec3(0.85, 0.68, 0.62);
    vec3 nightAmb = vec3(0.45, 0.55, 0.85);
    float dusk = 1.0 - abs(sunDirWorld().y * 2.5);
    vec3 amb = mix(nightAmb, mix(dayAmb, duskAmb, saturate(dusk) * 0.6), day);
    return mix(amb, vec3(0.7, 0.74, 0.8), rainStrength * 0.8);
}

// ---------------------------------------------------------------- stars
// Cells on a cube map around the sky; a few cells hold one small star each. Stars turn with the sun.
vec3 starField(vec3 dir) {
    vec3 sunDir = sunDirWorld();
    // Rotate into a frame tied to the sun's path so the stars move across the sky with time.
    vec3 axis = normalize(cross(sunDir, vec3(0.0, 0.0, 1.0)) + vec3(1e-4));
    vec3 b = normalize(cross(axis, sunDir));
    vec3 p = vec3(dot(dir, axis), dot(dir, b), dot(dir, sunDir));
    vec3 a = abs(p);
    vec2 face = a.x > a.y && a.x > a.z ? p.yz / a.x : (a.y > a.z ? p.xz / a.y : p.xy / a.z);
    float faceId = a.x > a.y && a.x > a.z ? sign(p.x) : (a.y > a.z ? 2.0 * sign(p.y) : 3.0 * sign(p.z));
    vec2 grid = face * 180.0;
    vec2 cell = floor(grid);
    float h = hash12(cell + faceId * 517.0);
    if (h < 0.965) return vec3(0.0);
    vec2 centre = vec2(hash12(cell + 11.3), hash12(cell + 29.7)) * 0.6 + 0.2;
    float d = length(fract(grid) - centre);
    float twinkle = 0.75 + 0.25 * sin(frameTimeCounter * (2.0 + h * 5.0) + h * 80.0);
    float brightness = (h - 0.965) / 0.035;
    vec3 tint = mix(vec3(1.0, 0.85, 0.7), vec3(0.75, 0.85, 1.0), hash12(cell + 3.1));
    float night = smoothstep(0.05, -0.15, sunDir.y);
    return tint * smoothstep(0.32, 0.0, d) * brightness * brightness * twinkle * 0.9 * night
           * (1.0 - rainStrength) * smoothstep(-0.02, 0.15, dir.y);
}

// ---------------------------------------------------------------- other dimensions
vec3 netherSky(vec3 dir) {
    return toLinear(fogColor) * 0.9;
}

vec3 endSky(vec3 dir) {
    vec3 base = mix(vec3(0.010, 0.006, 0.018), vec3(0.035, 0.018, 0.055), smoothstep(-0.4, 0.6, dir.y));
#ifdef END_NEBULA
    // Two layers of slowly drifting value noise make a faint purple-teal nebula.
    vec2 p = dir.xz / (abs(dir.y) + 0.6) * 2.0;
    float t = frameTimeCounter * 0.004;
    float n = 0.0, amp = 0.55;
    for (int i = 0; i < 4; i++) {
        vec2 c = floor(p), f = fract(p);
        f = f * f * (3.0 - 2.0 * f);
        float v = mix(mix(hash12(c), hash12(c + vec2(1, 0)), f.x), mix(hash12(c + vec2(0, 1)), hash12(c + 1.0), f.x), f.y);
        n += v * amp;
        p = p * 2.03 + t;
        amp *= 0.5;
    }
    n = smoothstep(0.45, 1.0, n);
    base += mix(vec3(0.10, 0.03, 0.16), vec3(0.03, 0.10, 0.12), saturate(dir.x * 0.5 + 0.5)) * n * 0.6;
    // Stars: reuse the cell trick with a fixed frame.
    vec3 a = abs(dir);
    vec2 face = a.x > a.y && a.x > a.z ? dir.yz / a.x : (a.y > a.z ? dir.xz / a.y : dir.xy / a.z);
    vec2 cell = floor(face * 140.0);
    float h = hash12(cell + (a.x > a.y && a.x > a.z ? 1.0 : (a.y > a.z ? 2.0 : 3.0)) * sign(dot(dir, vec3(1.0))) * 91.0);
    if (h > 0.975) base += vec3(0.9, 0.85, 1.0) * smoothstep(0.3, 0.0, length(fract(face * 140.0) - 0.5)) * (h - 0.975) * 30.0;
#endif
    return base;
}

#endif
