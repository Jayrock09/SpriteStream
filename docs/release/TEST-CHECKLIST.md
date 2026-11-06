# In-game test checklist — 0.1.0-beta.1+26.3

> Most of this list was run in the dev client on 2026-11-06 (OpenGL + Vulkan, RX 9070); results and
> screenshots are in `docs/perf/RESULTS.md`. What is left for a person: Iris 1.11.7 on Vulkan without SpriteStream (it crashed
> in testing, apparently in Iris itself), and judging the emission look.

Jar: `build/libs/spritestream-0.1.0-beta.1+26.3.jar`. Nothing below was run in the game during release prep;
everything marked **NEW** changed without an in-game check and needs one.

Setup: Prism 26.3 instance, Fabric Loader 0.19.5, Sodium 0.9.3-beta.1 (also try 0.9.2), Continuity 3.0.1,
Iris 1.12 snapshot (and Iris 1.11.7 from Modrinth for section 3b). Delete `config/spritestream.json` first so the new defaults are used. Keep
`logs/latest.log` for every run that goes wrong.

## 1. Startup and fallback
- [ ] Game starts with Sodium only (no Iris). Log has `[SpriteStream] base 64x, max 2048x, pool budget 2560 MiB`
      and `Patched Sodium terrain shader`.
- [ ] Game starts with Iris 1.12 snapshot. **NEW:** no `Streaming is off` line in the log (the Iris
      pre-flight check must pass on your Iris).
- [ ] **NEW:** with Iris **1.11.7** (from Modrinth) on **Vulkan**: game starts, log has
      `[SpriteStream] Iris 1.11.7+mc26.3 on Vulkan: Iris 1.11 runs shader packs on OpenGL only...`, and no
      `Streaming is off` line. Textures stream like with Sodium only (walk up to stone bricks: they sharpen).
      Opening Iris's shader screen shows its "switch to OpenGL" prompt — that is Iris, not us.
- [ ] **NEW:** an Iris older than 1.11 (if you can find one for 26.3), or a jar renamed to look like one: game
      starts, exactly one `[SpriteStream] Streaming is off: ...` line, textures load normally.
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

## 3b. Iris 1.11.7 — **NEW, nothing here has run in the game yet**
Set `preferredGraphicsBackend` to OpenGL (Video Settings, or `options.txt`), install Iris 1.11.7 from Modrinth
instead of the 1.12 snapshot.
- [ ] Game starts on **OpenGL**; log has `Iris 1.11.7+mc26.3 on OpenGL: shader-pack streaming uses the Iris 1.11
      OpenGL hooks` and `Patched Sodium terrain shader`.
- [ ] Shaders off: blocks sharpen up close (no-shader path on OpenGL).
- [ ] **Bliss with POM on**: colour, normals and POM depth all sharpen up close, no black or flickering blocks,
      no seams. Also Alpha Piscium or another LabPBR/POM pack.
- [ ] Emissive and metal blocks look right near and far (same as section 3).
- [ ] Toggle the shader pack on/off a few times: no crash, still streams.
- [ ] If anything shows the small 64x textures with a shader pack, check the log for
      `asked for an OpenGL texture but the game is not on OpenGL` and send the log.
- [ ] Switch back to **Vulkan** with Iris 1.11.7: the game asks to switch to OpenGL when opening shaders (Iris's
      own prompt); without shaders, streaming still works.
- [ ] Optional: Iris **1.12 snapshot on OpenGL** with Bliss (never tested on OpenGL so far).

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
