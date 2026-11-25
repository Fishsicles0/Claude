/* Evade Shaders - clouds, drawn behind terrain on sky pixels in deferred.fsh.
 * CLOUD_MODE 1: the same layer with two noise octaves and no self-shadowing (cheap, POTATO/LOW).
 * CLOUD_MODE 2: one cloud layer: fractal value noise on a plane, shaded with a second lookup towards the sun.
 * CLOUD_MODE 3: volumetric clouds: ray-marched through a slab, density from layered noise, light marched
 *               towards the sun (Beer-Lambert) with a powder term for dark-edged, bright-topped clouds.
 * Returns rgb = light scattered towards the camera, a = transmittance of the sky behind.
 */
#ifndef EVADE_CLOUDS
#define EVADE_CLOUDS

uniform sampler2D noisetex; // Iris' built-in 256x256 random noise texture (repeat wrap)

#define CLOUD_ALTITUDE 210.0
#define CLOUD_THICKNESS 70.0

float cloudNoise(vec2 p) { return texture(noisetex, p * (1.0 / 256.0)).r; }

float fbm2(vec2 p) {
    float v = cloudNoise(p) * 0.5;
    v += cloudNoise(p * 2.03 + 17.1) * 0.25;
    v += cloudNoise(p * 4.07 + 41.7) * 0.125;
    v += cloudNoise(p * 8.11 + 73.3) * 0.0625;
    return v / 0.9375;
}

vec2 cloudWind() { return vec2(frameTimeCounter * 2.2, frameTimeCounter * 0.7); }

float coverageNow() { return saturate(CLOUD_COVERAGE + rainStrength * 0.35); }

// Lighting colours shared by both cloud styles.
void cloudLights(out vec3 sunLight, out vec3 skyLight) {
    vec3 sunDir = sunDirWorld();
    vec3 sunT = bodyTransmittance(max(sunDir.y, 0.02)) * smoothstep(-0.06, 0.05, sunDir.y);
    vec3 moon = vec3(0.5, 0.6, 0.85) * 0.006 * smoothstep(-0.05, 0.1, -sunDir.y);
    sunLight = (sunT * 1.6 + moon) * SUN_E * 0.06 * (1.0 - rainStrength * 0.7);
    skyLight = skyAtmosphere(vec3(0.0, 1.0, 0.0)) * 1.1 + vec3(0.002);
}

#if CLOUD_MODE == 1
float cloudShape(vec2 p) { return cloudNoise(p) * 0.667 + cloudNoise(p * 2.03 + 17.1) * 0.333; }
#else
float cloudShape(vec2 p) { return fbm2(p); }
#endif

#if CLOUD_MODE == 1 || CLOUD_MODE == 2
vec4 drawClouds(vec3 dir, vec3 eye) {
    float planeY = CLOUD_ALTITUDE - eye.y;
    if (dir.y * planeY <= 0.0) return vec4(0.0, 0.0, 0.0, 1.0);
    float t = planeY / dir.y;
    if (t > 6000.0) return vec4(0.0, 0.0, 0.0, 1.0);
    vec2 p = (eye.xz + dir.xz * t + cloudWind()) * 0.006;
    float cov = coverageNow();
    float d = smoothstep(1.0 - cov, 1.0 - cov + 0.28, cloudShape(p));
    if (d <= 0.001) return vec4(0.0, 0.0, 0.0, 1.0);
    vec3 sunDir = sunDirWorld();
    // Self-shadow: how much denser the cloud gets a short way towards the sun.
#if CLOUD_MODE == 2
    float d2 = smoothstep(1.0 - cov, 1.0 - cov + 0.28, fbm2(p + sunDir.xz * 0.35));
    float shade = exp(-max(d2 - d * 0.6, 0.0) * 2.5);
#else
    float shade = 1.0 - d * 0.35;
#endif
    vec3 sunLight, skyLight;
    cloudLights(sunLight, skyLight);
    float forward = 1.0 + 1.5 * pow(saturate(dot(dir, sunDir)), 8.0); // silver lining near the sun
    vec3 col = sunLight * shade * forward * 0.55 + skyLight * 0.9;
    float fade = exp(-t * 0.00035);
    float a = d * 0.92 * fade;
    return vec4(col * a, 1.0 - a);
}
#endif

#if CLOUD_MODE == 3
float cloudDensity(vec3 p, float cov) {
    float h = (p.y - CLOUD_ALTITUDE) / CLOUD_THICKNESS;
    if (h < 0.0 || h > 1.0) return 0.0;
    vec2 q = (p.xz + cloudWind()) * 0.005;
    // Fake 3D noise: two 2D lookups slid apart by height.
    float n = fbm2(q + h * 0.35) * 0.75 + cloudNoise(q * 5.3 - h * 1.7) * 0.25;
    float profile = smoothstep(0.0, 0.25, h) * smoothstep(1.0, 0.45, h); // rounded bottoms, wispy tops
    return saturate((n - (1.0 - cov)) * 3.5) * profile;
}

vec4 drawClouds(vec3 dir, vec3 eye) {
    float y0 = CLOUD_ALTITUDE - eye.y, y1 = CLOUD_ALTITUDE + CLOUD_THICKNESS - eye.y;
    if (abs(dir.y) < 1e-3) return vec4(0.0, 0.0, 0.0, 1.0);
    float ta = y0 / dir.y, tb = y1 / dir.y;
    float t0 = max(min(ta, tb), 0.0), t1 = max(ta, tb);
    if (t1 <= 0.0 || t0 > 5000.0) return vec4(0.0, 0.0, 0.0, 1.0);
    t1 = min(t1, t0 + 900.0);
    const int STEPS = 18;
    float stepLen = (t1 - t0) / float(STEPS);
    float jitter = ignAnimated(gl_FragCoord.xy, frameCounter);
    vec3 sunDir = sunDirWorld();
    vec3 sunLight, skyLight;
    cloudLights(sunLight, skyLight);
    float cov = coverageNow();
    float phase = 0.6 + 1.6 * pow(saturate(dot(dir, sunDir)), 6.0);
    vec3 scatter = vec3(0.0);
    float trans = 1.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 p = eye + dir * (t0 + (float(i) + jitter) * stepLen);
        float d = cloudDensity(p, cov);
        if (d <= 0.0) continue;
        // Light march: 3 samples towards the sun.
        float od = 0.0;
        for (int j = 1; j <= 3; j++) od += cloudDensity(p + sunDir * (float(j) * 14.0), cov);
        float sunT = exp(-od * 14.0 * 0.12);
        float powder = 1.0 - exp(-d * 4.0);
        float sigma = d * 0.09;
        float stepT = exp(-sigma * stepLen);
        vec3 s = sunLight * sunT * powder * phase + skyLight * (0.55 + 0.45 * (p.y - CLOUD_ALTITUDE) / CLOUD_THICKNESS);
        scatter += trans * s * (1.0 - stepT);
        trans *= stepT;
        if (trans < 0.03) break;
    }
    float fade = exp(-t0 * 0.0003);
    return vec4(scatter * fade, mix(1.0, trans, fade));
}
#endif

#endif
