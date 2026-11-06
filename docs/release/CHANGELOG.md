# Changelog

## 0.1.0-beta.1+26.3 — first public beta

First release.

### Streaming
- Block textures are kept at a 64x base resolution; textures covering pixels on screen are streamed in at the
  resolution those pixels need (up to 2048x), inside a fixed VRAM pool (`vramBudgetMiB`, default 2560 MiB).
- Screen-space probe measures pixels per block, so zooming upgrades distant blocks.
- Background decoding with a per-frame upload budget and fade-in.
- Sharp magnification: close-up texels stay crisp with anti-aliased edges instead of a bilinear blur.

### Shader packs (Iris 1.11+)
- Normal and specular (LabPBR) maps stream alongside colour while a shader pack is active.
- Terrain shaders are patched so `texture`/`textureLod`/`textureGrad` reads (including ones inside helper
  functions, as in Bliss) go through the pool; POM keeps working.
- Iris's PBR maps are shrunk during resource loading, so enabling a shader pack does not stall the game.
- LabPBR-aware mipmaps for specular maps: emission and metal IDs are no longer averaged into wrong values.

### Compatibility
- Connected/random textures (Continuity, OptiFine CTM format, including Continuity's renamed tiles) stream.
- Entity textures in the block atlas, animated textures and the non-block atlases are capped instead of streamed.
- Iris 1.11.x (current public release) and 1.12+ are both supported. The hook set is picked at startup from
  what the installed Iris actually has: 1.12 binds the pool as texture views (Vulkan and OpenGL), 1.11 as
  OpenGL texture names (Iris 1.11 runs shader packs on OpenGL only; on Vulkan SpriteStream streams without
  shaders).
- Safe fallback: with an unsupported Iris (older than 1.11, or one whose internals changed) SpriteStream turns
  itself off with one clear log line instead of crashing.

### Performance
- The screen probe casts a fixed number of rays per second instead of per frame: with shaders off, frame rates
  went from ~235–510 to ~1200–1860 fps in testing (1440p, Optimum Realism 1024x); with shader packs it frees
  0.8–1.5 ms of render-thread time per frame.
- Patched shaders remember the last sprite per pixel, skipping repeated lookups in POM loops (+7% on close-ups
  with Bliss).
- No more ~1 s freeze the first time an item appears with a shader pack on: item and chest PBR maps are
  prepared while the pack loads.

### Loading
- PBR packs: `_n`/`_s` maps are no longer decoded at full size just to fill vanilla's block atlas
  (`skipPbrMapsInBlockAtlas`), which shortens loading and lowers RAM use.
