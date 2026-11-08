# CurseForge page — SpriteStream

Prepared against CurseForge's Project Moderation Policies (name, summary, description, avatar, sample images,
AI-image disclaimer). Nothing has been uploaded.

## Project settings

| Field | Value |
|---|---|
| **Project name** | SpriteStream |
| **Summary** (one sentence, not repeated in the description) | Streams high-resolution block textures in only where you are looking, so 512x–2048x packs fit in a fixed amount of video memory. |
| **Class / category** | Mods → Performance (plus Cosmetic if wanted) |
| **Avatar** | `docs/release/curseforge-avatar.png` (400x400 PNG) |
| **License** | GNU LGPL 3.0 (`LGPL-3.0-only`, see `COPYING.LESSER`) |
| **Source** | https://github.com/Jayrock09/SpriteStream |
| **Issues** | https://github.com/Jayrock09/SpriteStream/issues |

## File upload settings

| Field | Value |
|---|---|
| **File** | `build/libs/spritestream-0.1.0-beta.1+26.3.jar` |
| **Display name** | SpriteStream 0.1.0-beta.1 for Fabric 26.3 |
| **Release type** | Beta |
| **Game version** | 26.3 |
| **Mod loader** | Fabric |
| **Java** | 25 |
| **Environment** | Client |
| **Changelog** | contents of `docs/release/CHANGELOG.md` (the 0.1.0-beta.1 section) |
| **Related projects** | **Sodium** (by JellySquid) — Required Dependency · **Iris Shaders** (by coderbot) — Optional Dependency · **Continuity** (by Pepper_Bell) — Optional Dependency |

All three have Fabric 26.3 files on CurseForge (Sodium 0.9.3-beta.1 / 0.9.2, Continuity 3.0.1+26.3). Iris for
26.3 is 1.11.x; SpriteStream supports it (shader packs on OpenGL) and Iris 1.12 when it comes out. Sodium
0.9.3-beta.4 (7 Nov) declares itself incompatible with Iris ≤ 1.11.7, so shader-pack players need Sodium
0.9.2 – 0.9.3-beta.3 until a newer Iris is out; the description says so.

## Links

**Chosen: the repo is public** (option (a), Jayden, 6 Nov 2026). `github.com/Jayrock09/SpriteStream` and its
`/issues` page load without signing in, so the `fabric.mod.json` contact links in the built jar (Mod Menu's
Website, Source and Issues buttons) work as they are; no rebuild was needed. CurseForge's Source and Issues
fields above point to the same URLs. Public source also covers the LGPL's source requirement.

---

## Description (paste below this line)

> **AI was used to write the code for this project.** The idea, brainstorming, design and planning were all
> done by a human (Jayrock09). Only the coding was done with AI.
>
> *Screenshots on this page are unedited in-game captures.*

**SpriteStream lets 512x, 1024x and 2048x texture packs run inside a fixed amount of video memory.**

Normally a high-resolution pack keeps every block texture in VRAM at full size all the time. With a 1024x pack
that does not even fit: the game cannot build its texture atlas and falls back to vanilla textures. SpriteStream
changes how block textures are loaded:

- Every block texture is loaded at a small **base resolution (64x)**, so the always-loaded atlas stays tiny.
- Each frame, SpriteStream measures **how many pixels on your screen each block texture actually covers**,
  and streams in the full-resolution version only for those textures, at the resolution those pixels can show.
  Walk up to a wall and it sharpens; look away and the memory goes to something else. Zooming in (spyglass or a
  zoom mod) counts as well.
- All high-resolution textures share **one pool of fixed size**, allocated once from your VRAM budget
  (2.5 GB by default), so memory use never grows past what you set.
- Textures are decoded on background threads, uploaded a little each frame and faded in, so upgrades do not
  stutter or pop.

### With shader packs (Iris)
- Normal and specular maps (LabPBR) stream together with the colour texture, and parallax occlusion mapping
  (POM) keeps working — tested with Bliss and Alpha Piscium.
- Emissive textures glow only where the texture says so, and keep glowing on the small textures used for
  distant blocks.
- Iris 1.12+: shader packs on OpenGL and Vulkan. Iris 1.11.x: shader packs on OpenGL (Iris 1.11 itself does
  not run shader packs on Vulkan).
- With an Iris version SpriteStream does not recognise, it turns itself off and says so in the log; textures
  then load as if it was not installed.

### Connected textures
Connected and random textures (Continuity, OptiFine CTM format) stream like any other block texture.

### What it does not change
- Animated textures (water, lava, fire…) are not streamed; they are capped at 256x.
- Items, entities and the GUI use their own atlases, which are capped at 256x (configurable), not streamed.

### Requirements
- Minecraft 26.3, Fabric Loader 0.19+, Java 25
- **Sodium** 0.9.2 or newer (0.9.x) — required
- **Iris Shaders** 1.11+ — optional, for shader packs
- **Continuity** — optional, for connected textures

**Using shader packs?** Pick a Sodium that matches your Iris: Iris 1.11.x (the current release) works with
Sodium 0.9.2 up to 0.9.3-beta.3; Sodium 0.9.3-beta.4 refuses to start with it. Without Iris, any Sodium 0.9.x
from 0.9.2 works.

### Settings
`config/spritestream.json`:
- `vramBudgetMiB` (default 2560): size of the texture pool. On 8 GB cards try 1024–1536.
- `qualityBias` (default 1.0): lower is softer but needs less memory.
- `baseResolution` (default 64): resolution every block texture always has.

### Beta
Tested on an AMD RX 9070 (OpenGL and Vulkan) with Optimum Realism 1024x and Stratum 2048x, without shaders and
with Bliss. Please report problems with your pack, shader pack and GPU on the
[issue tracker](https://github.com/Jayrock09/SpriteStream/issues), and include `logs/latest.log`.
Source code: [github.com/Jayrock09/SpriteStream](https://github.com/Jayrock09/SpriteStream).
