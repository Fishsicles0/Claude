#version 330

// Evade's GUI shape primitive (see net.evadeclient.render.ui.UiShape). One quad per shape; every parameter rides in
// the vertex attributes (identical on all four corners) so thousands of shapes batch into one draw.
// The uniform blocks are copies of dynamictransforms.glsl and projection.glsl, like vanilla's gui.vsh.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;      // colour A (fill, or shadow / glow colour), straight alpha
in vec2 UV0;        // this corner relative to the shape centre, in device pixels
in vec4 ShapeA;     // half width, half height, border width (device px), mode flags
in vec4 ShapeR;     // corner radii TL, TR, BR, BL (device px)
in vec4 ShapeG;     // gradient: linear (gx, gy, start, kind) / radial (cx, cy, r, kind); arc: (a0, sweep, thickness, kind)
in vec4 ShapeL;     // fill: rim alpha, inner shade, grain, highlight x | shadow: -, -, sigma, offset y
in uvec4 ColorB;    // colour B (gradient end), 0..255
in uvec4 ColorC;    // border colour, 0..255

out vec2 local;
flat out vec4 colA;
flat out vec4 colB;
flat out vec4 colC;
flat out vec4 shapeA;
flat out vec4 shapeR;
flat out vec4 shapeG;
flat out vec4 shapeL;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    local = UV0;
    colA = Color * ColorModulator;
    colB = vec4(ColorB) / 255.0 * ColorModulator;
    colC = vec4(ColorC) / 255.0 * ColorModulator;
    shapeA = ShapeA;
    shapeR = ShapeR;
    shapeG = ShapeG;
    shapeL = ShapeL;
}
