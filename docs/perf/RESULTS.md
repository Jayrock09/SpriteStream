# Performance pass — results (branch `perf`)

All numbers come from the dev client set up like Jayden's instance (copied options, Sodium and Bliss settings;
his instance itself was not touched): **2560x1440, OpenGL, AMD RX 9070, Optimum Realism 1024x, Bliss
(Unstable), render distance 10, simulation 6, 4 GB heap**. The dev client runs only Sodium 0.9.3-beta.1,
Iris 1.12 snapshot and Continuity, not his other ~55 mods, so absolute fps are higher than what he sees in game
(he reported ~60). The before/after differences are what matter.

## How it was measured

`tools/bench.sh` + `Benchmark.java` (dev only): a fixed-seed world (seed 20261106, generated with the vanilla
server), a spectator camera parked at four fixed spots — **close-up** (stone brick, brick and jungle-log wall
2.5 blocks away), **landscape** (40 blocks above spawn), **village**, **forest** — 25 s warm-up, then 20 s of
every frame time, plus SpriteStream's own CPU time per frame. Time of day is frozen; the AFK 30 fps cap is off.
Each case ran at least twice; run-to-run noise is about ±1–2 fps (±7 on the 1000+ fps shaders-off runs).
Cells are **average fps (1% low)**.

## The ceiling (Bliss on)

| Case | close-up | landscape | village | forest |
|---|---|---|---|---|
| (a) SpriteStream, before (`0044e29`) | 115 (89) / 114 (88) | 116 (98) / 116 (95) | 107 (83) / 107 (84) | 103 (87) / 104 (86) |
| **(a) SpriteStream, after (this branch)** | **123 (71) / 123 (96)** | **116 (98) / 117 (92)** | **111 (88) / 109 (84)** | **112 (95) / 113 (94)** |
| (b) 64x base only, no streaming, shaders unpatched | 156 (125) | 119 (93) | 115 (96) | 115 (95) |
| (a′) streaming on but shaders unpatched (before) | 155 (115) | 119 (98) | 116 (90) | 122 (96) |
| (c) full 1024x without SpriteStream | — | — | — | — |
| (d) vanilla textures | 189 (134) | 213 (162) | 192 (136) | 177 (138) |

- **(c) does not run.** Without SpriteStream the 1024x pack fails to load (`Stitching` error: the block atlas
  is larger than the GPU's maximum texture size) and Minecraft falls back to vanilla textures.
- (d) vs (b): most of the gap to vanilla is the pack itself under Bliss (LabPBR + POM), not SpriteStream.
- (a′) vs (b): with Bliss the game is GPU-bound, so the probe's CPU time did not show up in fps.
- (a) vs (a′): the remaining SpriteStream cost with Bliss is in the patched shaders, mostly POM sampling
  1024x data instead of 64x — the price of the detail itself.

## Shaders off

| | close-up | landscape | village | forest |
|---|---|---|---|---|
| before | 475 (228) / 512 (266) | 375 (223) / 390 (236) | 235 (150) / 234 (136) | 372 (228) / 354 (208) |
| **after** | **1863 (697) / 1391 (552)** | **1609 (694) / 1211 (602)** | **1344 (501) / 1212 (456)** | **1550 (668) / 1442 (624)** |
| (b) 64x base only | 2257 (813) | 1759 (711) | 1637 (590) | 1824 (767) |

## Top costs found and what changed

1. **Screen probe CPU time — 1.5–3.5 ms of every frame on the render thread.** It cast its whole 64x36 ray grid
   every frame, so the faster the game ran, the more it cost. Now it casts at most 60 full grids per second:
   at ≤60 fps nothing changes; above that the same rays per second are spread across frames.
   Probe time per frame: shaders off 1.5–3.5 → 0.06–0.18 ms; Bliss 1.6–3.5 → 0.8–2.0 ms
   (fps unchanged with Bliss because it is GPU-bound, but that render-thread time is now free for his other
   mods). Commit `15b10ee`.
2. **Pool lookup in patched shaders (GPU), worst under POM: +2.3 ms per frame on the close-up.** Each patched
   texture call read the sprite-id map and four table entries. The shaders now remember the last sprite per
   fragment and skip those reads while the UV stays inside it. Close-up 114.6/114.3 → 123.0/122.9 fps, forest
   +3–4%, identical output. Commit `2776813`.
3. **~0.9 s freeze the first time an item shows up with shaders on** (5 of 7 village runs). Iris builds its
   item/chest normal and specular atlases lazily and decodes every full-size map on the render thread. Those
   maps are now shrunk while the pack loads, like the block maps already were. Village longest frame
   ~880 ms → 15.7 ms, village 1% low 18 → 84–88 fps, load time unchanged, about 57 MB native memory for this
   pack. The same commit fixes a race where the item atlas could load before the block atlas and miss the
   per-reload reset. Commit `c9640fb`.

Tried and dropped: skipping the base-atlas read when a texture is fully faded in (identical output, but
123.2/130.1/123.4 vs 123.0/122.9/122.8 fps — within noise).

## Quality — screenshot pairs (`img/`)

| Pair | Result |
|---|---|
| `closeup-before-after.jpg`, `closeup-zoom-before-after.jpg` (8x) | identical detail; pixel differences equal to run-to-run noise (Bliss TAA) |
| `village-before-after.jpg`, `forest-before-after.jpg` | same; differences only on moving mobs, water and clouds |
| `sharpen-old-new.jpg` (shaders off, 8x) | old vs new sharpening formula: mean difference 0.0044, the same as two identical runs |
| `emission-old-new.jpg` (night) | old mips: the whole redstone block glows; new: only its emissive pattern glows, like the full-res texture |
| `metal-far-old-new.jpg` (day, 38 blocks) | metals look the same; no speckles |
| `iris112-vs-iris111-opengl.jpg` | Iris 1.11.7 on OpenGL streams like 1.12 |

## Things only Jayden can judge

- The emission change is visible on purpose (`emission-old-new.jpg`): distant/medium emissive blocks now glow
  only where the texture says so. Check it looks right with your shader settings.
- His real fps with all his mods: the dev client has none of them. Worth one look at F3 in the same spots.

## TEST-CHECKLIST results (dev client, 2026-11-06)

Same machine and setup as above. "Not run" items need a person (or Mod Menu, which the dev client does not have).

| Item | Result |
|---|---|
| Sodium only: starts, `Patched Sodium terrain shader` | **PASS** (OpenGL and Vulkan) |
| Iris 1.12 snapshot: no `Streaming is off` line | **PASS** |
| Iris 1.11.7 on OpenGL: `... uses the Iris 1.11 OpenGL hooks`, streams with Bliss + POM | **PASS** — 136/124/114/114 fps in the four scenes (`img/iris112-vs-iris111-opengl.jpg`) |
| Iris 1.11.7 on Vulkan | **FAIL, not ours**: our log line appears, then `VK_ERROR_DEVICE_LOST` on the loading screen. It also crashes with SpriteStream's streaming off and no pool bindings, so it is Iris 1.11 on Vulkan itself. Iris 1.11 needs OpenGL for shader packs anyway. Jayden: one quick check without the SpriteStream jar would confirm. |
| Iris too old (fake 1.10.9) | Fabric refuses to start (Sodium itself breaks Iris ≤ 1.11.2), so SpriteStream never sees it |
| Iris that changed (fake 1.13.0 with 1.11 internals) | **PASS** — one line `Streaming is off: Iris 1.13.0+mc26.3 is newer than 1.11 but lacks the 1.12 sampler API`, game runs normally |
| Sodium 0.9.2 instead of 0.9.3-beta.1 | **PASS** |
| Sharpening rewrite (shaders off, 8x zoom) | **PASS** — identical to the old formula within run-to-run noise (`img/sharpen-old-new.jpg`) |
| Distant terrain, no black/garbage blocks, no edge lines | **PASS** (landscape/village/forest screenshots) |
| Emissive near and far (Bliss) | **PASS**, judge it yourself — only the emissive pattern glows now (`img/emission-old-new.jpg`) |
| Metal blocks far away | **PASS** (`img/metal-far-old-new.jpg`) |
| Turning fast (90°/s spin over the village) | **PASS** — 110 fps, 1% low 79, longest frame 77 ms (Iris 1.11: 118 / 85) |
| Shaders off → on → off (`Iris.toggleShaders`) | **PASS** — no crash, streams after each; longest frame 160–210 ms during the switch |
| F3+T resource reload | **PASS** — streams again afterwards (2.4 s is the reload screen itself) |
| VRAM flat while moving (Bliss, 1440p) | **PASS** — game total ~4.8 GB dedicated, flat; 3.9 GB with shaders off |
| Load time, Stratum 2048x | without Iris: 46 s → **24 s** with `skipPbrMapsInBlockAtlas`; with Iris ~46 s either way (the maps are prepared for Iris during load) |
| No flat purple/black blocks from the PBR placeholders | **PASS** in all screenshots |
| Zoom (spyglass), CTM glass/bookshelves, items in hand, Mod Menu entry | Not run |
