/*
 * Evade Shaders - free with Evade Client (evadeclient.com).
 * Every user option lives here so Iris can list it on the shader options screen.
 * Defaults are the MEDIUM profile; shaders.properties defines POTATO..ULTRA on top of these.
 * Option syntax (read by Iris):  #define NAME value // [allowed values]   or   #define FLAG / //#define FLAG
 */
#ifndef EVADE_SETTINGS
#define EVADE_SETTINGS

// ---------------------------------------------------------------- shadows
#define SHADOWS                          // Sun and moon shadows (a shadow map). Off = vanilla-cost lighting.
const int shadowMapResolution = 1536;    // [512 768 1024 1536 2048 3072 4096]
const float shadowDistance = 96.0;       // [48.0 64.0 80.0 96.0 128.0 160.0 192.0 256.0]
const float shadowDistanceRenderMul = 1.0;
const float sunPathRotation = -25.0;     // [-45.0 -35.0 -25.0 -15.0 0.0 15.0 25.0 35.0 45.0]
const bool shadowHardwareFiltering0 = true;
#define SHADOW_FILTER 1                  // [0 1 2]
#define SHADOW_SOFTNESS 1.0              // [0.5 0.75 1.0 1.5 2.0 3.0]
//#define COLORED_SHADOWS                // Light through stained glass is tinted
//#define FOLIAGE_TRANSLUCENCY           // Leaves and grass glow softly when backlit by the sun

// ---------------------------------------------------------------- lighting
#define WARM_BLOCKLIGHT                  // Torches, lanterns and fire cast warm light (brightness stays vanilla)
#define EMISSIVE_BLOCKS                  // Glowstone, lava, lanterns etc. glow in their own colour
#define EMISSIVE_STRENGTH 1.0            // [0.5 0.75 1.0 1.5 2.0 3.0]
#define SUNLIGHT_STRENGTH 1.0            // [0.6 0.8 1.0 1.2 1.4]

// ---------------------------------------------------------------- sky & atmosphere
#define SKY_MODEL 1                      // [0 1]
#define STARS                            // Procedural starfield at night
#define CLOUD_MODE 2                     // [0 1 2 3]
#define CLOUD_COVERAGE 0.45              // [0.2 0.3 0.4 0.45 0.5 0.6 0.7 0.8]
//#define VOLUMETRIC_LIGHT               // Light shafts (god rays) through gaps in leaves and terrain
#define VL_SAMPLES 8                     // [6 8 12 16 24]
#define VL_STRENGTH 1.0                  // [0.5 0.75 1.0 1.5 2.0]
#define FOG_DENSITY 1.0                  // [0.0 0.5 0.75 1.0 1.5 2.0 3.0]
#define BORDER_FOG                       // Hide the edge of the render distance with soft fog

// ---------------------------------------------------------------- water & reflections
#define WATER_WAVES                      // Animated wave normals on water
#define WAVE_HEIGHT 1.0                  // [0.25 0.5 0.75 1.0 1.5 2.0]
#define WATER_REFLECTIONS 2              // [0 1 2]
#define WATER_REFRACTION                 // Bend the view through the water surface
//#define GLASS_REFLECTIONS              // Glass, stained glass and ice also reflect
#define SSR_STEPS 16                     // [12 16 24 32 48]
//#define WATER_CAUSTICS                 // Rippling light patterns while you are underwater
#define UNDERWATER_WOBBLE                // Gentle screen wobble while underwater

// ---------------------------------------------------------------- foliage
#define WAVING_PLANTS                    // Grass, flowers and crops sway in the wind
#define WAVING_LEAVES                    // Leaves and vines sway in the wind
#define WIND_STRENGTH 1.0                // [0.5 0.75 1.0 1.5 2.0]

// ---------------------------------------------------------------- post processing
#define TAA                              // Temporal anti-aliasing: smooth edges, removes shimmer
#define SHARPENING 0.35                  // [0.0 0.15 0.25 0.35 0.5 0.75 1.0]
#define BLOOM                            // Soft glow around bright lights and the sun
#define BLOOM_STRENGTH 1.0               // [0.25 0.5 0.75 1.0 1.5 2.0]
#define TONEMAP 0                        // [0 1 2]
#define EXPOSURE 1.0                     // [0.6 0.7 0.8 0.9 1.0]
#define SATURATION 1.05                  // [0.6 0.8 0.9 1.0 1.05 1.1 1.2 1.3 1.5]
#define CONTRAST 1.0                     // [0.8 0.9 1.0 1.1 1.2]
#define VIGNETTE                         // Slightly darker screen corners
#define VIGNETTE_STRENGTH 0.3            // [0.1 0.2 0.3 0.4 0.6]

// ---------------------------------------------------------------- dimensions
#define NETHER_HAZE                      // Thick, warm haze in the Nether
#define END_NEBULA                       // Swirling nebula and stars in the End sky

// Iris lists a boolean #define as an option only when an #ifdef names it, so reference each one here.
#ifdef SHADOWS
#endif
#ifdef COLORED_SHADOWS
#endif
#ifdef FOLIAGE_TRANSLUCENCY
#endif
#ifdef WARM_BLOCKLIGHT
#endif
#ifdef EMISSIVE_BLOCKS
#endif
#ifdef STARS
#endif
#ifdef VOLUMETRIC_LIGHT
#endif
#ifdef BORDER_FOG
#endif
#ifdef WATER_WAVES
#endif
#ifdef WATER_REFRACTION
#endif
#ifdef GLASS_REFLECTIONS
#endif
#ifdef WATER_CAUSTICS
#endif
#ifdef UNDERWATER_WOBBLE
#endif
#ifdef WAVING_PLANTS
#endif
#ifdef WAVING_LEAVES
#endif
#ifdef TAA
#endif
#ifdef BLOOM
#endif
#ifdef VIGNETTE
#endif
#ifdef NETHER_HAZE
#endif
#ifdef END_NEBULA
#endif

// ---------------------------------------------------------------- internal (not options)
#define DEBUG_VIEW 0                     // [0 1 2 3 4 5]
#define SHADOW_DISTORT 0.85              // how strongly shadow texels are concentrated near the player
#define WATER_ID 1
#define PLANT_ID 2
#define TALL_PLANT_ID 3
#define LEAVES_ID 4
#define GLASS_ID 5
#define EMISSIVE_ID 6
#define LAVA_ID 7
#define ICE_ID 8

#endif
