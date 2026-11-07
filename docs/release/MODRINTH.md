# Modrinth page — SpriteStream

> **ON HOLD (Nov 2026).** Release target is CurseForge for now (see `CURSEFORGE.md`). Modrinth's content rules
> (section 6, Aug 2026) do not allow mostly-AI-written projects to be public. Kept for later.

**Name:** SpriteStream
**Summary (one line, ≤ 256 chars):** Run 512x–2048x texture packs (LabPBR/POM included) in a fixed VRAM budget. Block textures stay small until they actually cover pixels on screen.
**Categories:** Optimization, Utility · **Environment:** Client only · **Loader:** Fabric · **Game version:** 26.3
**License:** LGPL-3.0-only · **Source:** https://github.com/Jayrock09/SpriteStream (public since 6 Nov 2026)
**Version:** 0.1.0-beta.1+26.3 · **Release channel:** Beta
**Dependencies:** Sodium (required, 0.9.2+), Iris (optional, 1.11+), Continuity (optional)

---

## Description (paste below this line)

**SpriteStream lets huge texture packs run on normal graphics cards.**

A 2048x pack normally needs every block texture in video memory at full size, all the time — tens of gigabytes
for the bigger packs. SpriteStream keeps every block texture at a small base resolution (64x) and streams in
the full-resolution version only for the textures that are actually on screen, at the resolution the pixels
on your screen can show. Walk up to a wall and it sharpens; look away and the memory goes back to something
else. Zooming in counts too.

All of it lives in **one fixed-size pool** that is allocated once from your VRAM budget, so memory use never
grows past what you set.

### Features
- 512x, 1024x and 2048x block textures within a fixed VRAM budget (2.5 GB by default, configurable)
- Resolution chosen per texture from how many **screen pixels** it covers, not from distance
- **Shader packs (Iris 1.11+):** colour, normal and specular (LabPBR) maps stream together; POM keeps working
- **Connected textures** (Continuity / OptiFine CTM format) stream like any other block texture
- Upgrades decode in the background and fade in; uploads are rate-limited per frame to avoid stutter
- No vendor-specific code: built on Minecraft's own render API (tested on Vulkan; OpenGL and AMD reports welcome)

### Requirements
- Minecraft 26.3, Fabric Loader 0.19+
- **Sodium** 0.9.2 or newer (0.9.x)
- Optional: **Iris 1.11 or newer** for shader packs.
  - Iris 1.12+: shader packs on Vulkan and OpenGL.
  - Iris 1.11.x: shader packs on OpenGL (Iris 1.11 itself does not run shader packs on Vulkan; there you get
    streaming without shaders).
  - With an older Iris, or one SpriteStream does not recognise, it turns itself off and says so in the log
    (textures then load as if it was not installed) — it will not crash the game.
- Optional: **Continuity** for connected textures

### What is not streamed (yet)
- Animated textures (water, lava, fire…) are capped at 256x
- Items in hand / in the GUI and entity textures are capped at 256x (configurable)
- The shadow pass of shader packs uses the base textures (it only needs the cut-out shape)

### Settings
`config/spritestream.json` — the important ones:
- `vramBudgetMiB` (default 2560): total pool size. On 8 GB cards try 1024–1536.
- `qualityBias` (default 1.0): lower is softer but needs less memory.
- `baseResolution` (default 64): resolution every block texture always has.

### This is a beta
It has been tested with Optimum Realism 512x and Stratum 1024x, without shaders and with Bliss and
Alpha Piscium. Please report problems with your pack, shader pack and GPU on the issue tracker, and attach
`logs/latest.log` (SpriteStream logs a status line every few seconds).
