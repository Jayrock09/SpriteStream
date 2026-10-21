# SpriteStream (Fabric 26.3, requires Sodium; Iris 1.12+ optional)

Lets huge block texture packs (512x–2048x, including LabPBR packs with POM) run inside a fixed VRAM budget.

- Every block texture is stored in the atlas at a small **base resolution** (64x by default). Iris builds its
  `normals`/`specular` atlases at the same size, so those stay small too.
- A grid of rays cast through the screen every frame measures how many **screen pixels each block texture
  actually covers** (`screenHeight / (2 · depth · tan(fovY/2))` pixels per block). Zooming lowers the FOV,
  so zoomed-in distant blocks are upgraded the same way near ones are.
- Textures that need more than the base get a block in the **high-res pool**. The pool is one large texture
  per plane: colour, plus normal and specular while an Iris shader pack is active. A texture takes a
  power-of-two square sized to the resolution it needs, so resolutions share the space according to what is
  on screen. The pool is allocated once from `vramBudgetMiB` and never grows. When it is full, textures
  holding more than they currently need are evicted, or the request drops a step.
- Upgrades are decoded on background threads from the pack's original PNGs (`_n`/`_s` too), uploaded under a
  per-frame byte budget, and faded in over the base texture.
- **Without a shader pack:** Sodium's terrain shader is patched to sample the pool.
- **With an Iris shader pack:** every `texture`, `textureLod` and `textureGrad` call on `gtexture`, `normals`
  or `specular` in the pack's terrain programs is routed through the pool. Atlas UVs are kept, so POM code that
  walks UVs inside a tile keeps working. The pool is registered with Iris's own sampler system, so it binds
  on both Iris's OpenGL and Vulkan backends.

Tested on Vulkan with Optimum Realism 512x and Stratum 1024x, without shaders and with Bliss (POM on) and
Alpha Piscium.

## Config (`config/spritestream.json`)

| Key | Default | Meaning |
|---|---|---|
| `baseResolution` | 64 | Always-resident resolution of block textures |
| `maxResolution` | 2048 | Largest pool block |
| `vramBudgetMiB` | 2560 | Total pool size, shared by all planes (3 planes with a shader pack, so each gets a third) |
| `qualityBias` | 1.0 | Texels per screen pixel to aim for (lower = less VRAM, softer) |
| `otherAtlasResolution` | 256 | Cap for item/particle/chest/etc. atlases (0 = off; GUI is never capped) |
| `animatedResolution` | 128 | Cap for animated textures (these are not streamed) |
| `probeColumns` / `probeRows` | 64 / 36 | Probe ray grid (jittered 4×4 over 16 frames) |
| `uploadMiBPerFrame` | 24 | Upload budget per frame |
| `evictDelaySeconds` | 3.0 | How long a texture keeps its resolution after it stops being needed |
| `fadeSeconds` | 0.25 | Fade-in time for upgrades |
| `preparePbrMapsAtLoad` | true | With Iris, shrink `_n`/`_s` maps while the pack loads so turning shaders on does not stall the game |

## Known limits

- The pool is uncompressed RGBA8: the 26.3 render API has no BCn formats.
- Shader packs: `texelFetch` on the atlases and sampling in vertex/geometry shaders still read the base atlas;
  the shadow pass is not patched (it only needs cut-out alpha). An explicit `textureLod(..., 0)` is treated as
  "sharpest pool level".
- LabPBR specular mips are plain averages, so emission edges in distant mips can be slightly off.
- Turning a shader pack on or off rebuilds the pool once (it adds or drops the normal/specular planes).
- Connected/random textures (Continuity, OptiFine CTM format) are linked to their blocks by reading the pack's
  `optifine/ctm/**.properties` (`matchTiles`, `matchBlocks`, `tiles`); formats other than OptiFine CTM are not.
- Block items in hand and GUI, and entity textures, are not streamed.
- The whole pack is still decoded once at load to build the base atlas (with Iris, the `_n`/`_s` maps too, so
  that Iris's PBR atlas build is instant later). Stratum 2048x takes about 45 s on the loading screen.

## Iris version

The shader-pack hooks use the sampler and shader-transform API of **Iris 1.12**, which is not on Modrinth yet
(the latest public 26.3 release is 1.11.7). The mod only loads those hooks on Iris 1.12+. With an older Iris
installed it turns itself off and logs a warning, because a shader pack would otherwise show only the small
base textures.

## Build / test

Needs JDK 25. Sodium is fetched from Modrinth. To include the Iris hooks, point `irisJar` at an Iris 1.12 jar
in a `local.properties` file (not committed), or pass `-PirisJar=<path>`:

```
irisJar=C:/path/to/iris-fabric-1.12.0-snapshot+mc26.3.jar
```

Without it, the build leaves the Iris hooks out (Sodium-only).

```
gradlew build                                              # jar in build/libs
gradlew runClient -PtestWorld="<world in run/saves>"       # automated A/B screenshots into run/spritestream-test
gradlew runClient -Piris -PtestWorld="<world>"             # same, with Iris (shader pack set in run/config/iris.properties)
gradlew runClient -Pbaseline -PtestWorld="<world>"         # SpriteStream disabled, for comparisons
```

The test runs expect a world in `run/saves` and packs in `run/resourcepacks` / `run/shaderpacks`; those are
not part of the repository.

## License

LGPL-3.0-only. See `COPYING.LESSER` and `COPYING`.
