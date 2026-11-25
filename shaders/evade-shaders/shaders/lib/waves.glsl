/* Evade Shaders - water surface. A small sum of directional "sharpened sine" waves
 * (the classic approach from GPU Gems 1, ch. 1), differentiated analytically for the normal. */
#ifndef EVADE_WAVES
#define EVADE_WAVES

vec3 waterWaveNormal(vec2 xz, float t) {
    vec2 grad = vec2(0.0);
    float amp = 0.030, wavelength = 6.0, angle = 0.4;
    for (int i = 0; i < 6; i++) {
        vec2 dir = vec2(cos(angle), sin(angle));
        float k = TAU / wavelength;
        float speed = sqrt(9.8 / k) * 0.55;  // deep-water dispersion, slowed for a calm look
        float ph = (dot(dir, xz) - speed * t) * k;
        // exp(sin) shape gives sharper crests and wider troughs than a pure sine
        float s = exp(sin(ph) - 1.0);
        grad += dir * (amp * k * s * cos(ph));
        amp *= 0.72; wavelength *= 0.62; angle += 2.39996;
    }
    return normalize(vec3(-grad.x * WAVE_HEIGHT, 1.0, -grad.y * WAVE_HEIGHT));
}

#endif
