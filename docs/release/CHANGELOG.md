# Changelog

## 0.1.0-beta.1+26.3 — first public beta

First release.

### Streaming
- Block textures are kept at a 64x base resolution; textures covering pixels on screen are streamed in at the
  resolution those pixels need (up to 2048x), inside a fixed VRAM pool (`vramBudgetMiB`, default 2560 MiB).
- Screen-space probe measures pixels per block, so zooming upgrades distant blocks.
- Background decoding with a per-frame upload budget and fade-in.
- Sharp magnification: close-up texels stay crisp with anti-aliased edges instead of a bilinear blur.

### Shader packs (Iris 1.12+)
- Normal and specular (LabPBR) maps stream alongside colour while a shader pack is active.
- Terrain shaders are patched so `texture`/`textureLod`/`textureGrad` reads (including ones inside helper
  functions, as in Bliss) go through the pool; POM keeps working.
- Iris's PBR maps are shrunk during resource loading, so enabling a shader pack does not stall the game.
- LabPBR-aware mipmaps for specular maps: emission and metal IDs are no longer averaged into wrong values.

### Compatibility
- Connected/random textures (Continuity, OptiFine CTM format, including Continuity's renamed tiles) stream.
- Entity textures in the block atlas, animated textures and the non-block atlases are capped instead of streamed.
- Safe fallback: with an unsupported Iris (older than 1.12, or one whose internals changed) SpriteStream turns
  itself off with one clear log line instead of crashing.

### Loading
- PBR packs: `_n`/`_s` maps are no longer decoded at full size just to fill vanilla's block atlas
  (`skipPbrMapsInBlockAtlas`), which shortens loading and lowers RAM use.
