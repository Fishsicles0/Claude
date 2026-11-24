#version 330

// Evade Motion Blur: mixes this frame with the blended result of the previous one.
// ControlSampler is a 1x1 texture the client updates: red = how much of the previous frame to keep (0 seeds).
// A freshly created "previous" target is cleared to transparent black, so its alpha also turns blending off.

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;
uniform sampler2D ControlSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec3 current = texture(InSampler, texCoord).rgb;
    vec4 previous = texture(PrevSampler, texCoord);
    float keep = clamp(texture(ControlSampler, vec2(0.5)).r, 0.0, 0.95) * previous.a;
    fragColor = vec4(mix(current, previous.rgb, keep), 1.0);
}
