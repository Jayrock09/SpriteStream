# In-game test checklist — 0.1.0-beta.1+26.3

Jar: `build/libs/spritestream-0.1.0-beta.1+26.3.jar`. Nothing below was run in the game during release prep;
everything marked **NEW** changed without an in-game check and needs one.

Setup: Prism 26.3 instance, Fabric Loader 0.19.5, Sodium 0.9.3-beta.1 (also try 0.9.2), Continuity 3.0.1,
Iris 1.12 snapshot. Delete `config/spritestream.json` first so the new defaults are used. Keep
`logs/latest.log` for every run that goes wrong.

## 1. Startup and fallback
- [ ] Game starts with Sodium only (no Iris). Log has `[SpriteStream] base 64x, max 2048x, pool budget 2560 MiB`
      and `Patched Sodium terrain shader`.
- [ ] Game starts with Iris 1.12 snapshot. **NEW:** no `Streaming is off` line in the log (the Iris
      pre-flight check must pass on your Iris).
- [ ] **NEW:** with Iris **1.11.x** (from Modrinth) instead: game starts, log has exactly one
      `[SpriteStream] Streaming is off: Iris 1.11.x is older than 1.12...` line, textures look like vanilla
      (full pack resolution or the pack's normal look), no crash when turning a shader pack on.
- [ ] **NEW:** with Sodium **0.9.2** instead of 0.9.3-beta.1: starts and streams (code is identical, just confirm).
- [ ] Mod menu / mod list shows the new icon, description, version `0.1.0-beta.1+26.3`, LGPL license.

## 2. Without a shader pack
Packs: Optimum Realism 512x, Stratum 1024x, Stratum 2048x.
- [ ] Walk up to stone bricks, bricks, logs: they sharpen to full resolution, no 64x blocks left close up.
- [ ] **NEW (sharpening rewrite):** close-up texels are crisp with smooth 1-pixel edges, exactly like before —
      no shimmering, no blur, no seams at texel edges. Compare with the previous jar if unsure.
- [ ] Distant terrain looks normal (no black/garbage blocks, no glowing lines at block edges).
- [ ] Zoom (spyglass) on a far block: it sharpens.
- [ ] CTM with Continuity: glass panes, bookshelves, sandstone, Optimum Realism's connected blocks stream to
      high res too.
- [ ] Turn quickly / fly fast: no stutter, upgrades fade in.
- [ ] Animated textures (water, lava, fire, sea lantern) animate and look fine at 256x.
- [ ] Items in hand / inventory and mobs look fine (capped at 256x).

## 3. With shader packs (Iris 1.12)
Shader packs: Bliss (POM on), Alpha Piscium, plus Kappa / DERCODE / Pyvtron if you have time.
- [ ] Turning a shader pack on does not freeze the game (PBR maps were prepared at load).
- [ ] Colour, normals and POM depth all get sharp up close; POM has no seams or swimming.
- [ ] **NEW (LabPBR specular mips):** emissive blocks (lanterns, glowstone, ores in PBR packs, redstone
      lamps) glow correctly up close **and at a distance** (distant glow should look a bit weaker than
      before — that is the fix, it was too strong before). Metal blocks (gold, iron, copper) stay metallic at
      a distance, without speckles or odd colours.
- [ ] No black or glowing lines at block edges.
- [ ] Toggle shaders on/off a few times: no crash, pool rebuilds (log line), still streams.

## 4. Loading time and memory — **NEW (`skipPbrMapsInBlockAtlas`)**
- [ ] Stratum 2048x: note the loading-screen time (was ~45 s). It should be noticeably shorter.
- [ ] With Iris + shader pack: normal/specular maps still work (they come from Iris's own atlases, not the
      block atlas slots that now hold placeholders).
- [ ] No block in the world shows a flat purple-blue or black texture (that would mean a real texture was
      mistaken for a PBR map — note its name).
- [ ] Optional: set `"skipPbrMapsInBlockAtlas": false`, reload (F3+T), confirm nothing looks different.

## 5. VRAM budget
- [ ] Task Manager → GPU dedicated memory with Stratum 2048x + shaders stays flat while flying around.
- [ ] Set `vramBudgetMiB` to 1024: still works, slightly softer up close, lower memory.
- [ ] (If you can borrow one) an AMD card and/or the OpenGL backend: everything in section 2 works.

## 6. Reloads
- [ ] F3+T (resource reload) with a pack on: still streams afterwards.
- [ ] Switch between two packs in the resource pack menu: no crash, the new pack streams.
- [ ] Leave and re-join a world: still streams.
