/* Evade Shaders - uniforms shared by the programs (names as documented by Iris/OptiFine).
 * Unused declarations cost nothing: the GLSL compiler drops them. */
#ifndef EVADE_UNIFORMS
#define EVADE_UNIFORMS

uniform mat4 gbufferModelView, gbufferModelViewInverse;
uniform mat4 gbufferProjection, gbufferProjectionInverse;
uniform mat4 gbufferPreviousModelView, gbufferPreviousProjection;
uniform mat4 shadowModelView, shadowProjection;

uniform vec3 cameraPosition, previousCameraPosition;
uniform vec3 sunPosition, moonPosition, shadowLightPosition, upPosition;
uniform vec3 fogColor, skyColor;

uniform float viewWidth, viewHeight, aspectRatio;
uniform float near, far;
uniform float sunAngle;
uniform float frameTimeCounter;
uniform float rainStrength, thunderStrength, wetness;
uniform float nightVision, blindness, darknessFactor;
uniform float fogStart, fogEnd;
uniform float cloudHeight;

uniform int frameCounter;
uniform int worldTime;
uniform int isEyeInWater;
uniform int moonPhase;
uniform int renderStage;
uniform ivec2 eyeBrightnessSmooth;

uniform bool hasSkylight; // false in the Nether
uniform bool hasCeiling;  // true in the Nether
uniform int bedrockLevel, logicalHeightLimit;

// Since 1.21.9 the End has sky light too, so it is told apart by its shape: no ceiling, floor at y >= 0, 256 high.
bool isEnd()       { return !hasCeiling && bedrockLevel >= 0 && logicalHeightLimit <= 256; }
// Sun, moon, sky and weather: the Overworld (and dimensions built like it).
bool isOverworld() { return hasSkylight && !hasCeiling && !isEnd(); }

#endif
