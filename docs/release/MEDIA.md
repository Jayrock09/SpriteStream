# Screenshots and videos needed (Jayden)

Modrinth shows the gallery above the description, so the first image matters most. Use 1920x1080, hide the
HUD (F1), same seed/spot for before/after pairs. Name files as below so they match the page text.

## Screenshots
1. **hero.png** — Stratum (or Optimum Realism) with a shader pack (Bliss), a close-up wall with POM, golden
   hour. This is the gallery cover.
2. **compare-vram-off.png / compare-vram-on.png** — the same view with SpriteStream off (vanilla + Sodium) and
   on, with the F3 screen or Task Manager → GPU memory visible. The point: same look, far less VRAM.
   (If vanilla runs out of VRAM or fails to load the pack, screenshot that error instead — it makes the point.)
3. **closeup-sharp.png** — face pressed against a block (stone bricks or a log), no shaders, showing crisp
   2048x texels.
4. **distance.png** — a long view (village or mountain) showing that distant blocks look normal at 64x.
5. **ctm.png** — connected textures (glass panes, bookshelves or sandstone) from a CTM pack with Continuity.
6. **pbr.png** — shader pack with emissive and metal blocks (lanterns, gold/iron blocks, glowstone) to show
   specular/emission is right.
7. **zoom.png** — a distant block zoomed in (spyglass or a zoom mod) showing it sharpens.
8. **config.png** (optional) — `config/spritestream.json` open in an editor.

## Videos (short, 15–40 s, no music needed)
1. **walk-up.mp4** — walk slowly towards a wall from ~20 blocks away; textures sharpen as you approach. Best
   with `-Dspritestream.debug` off. This is the clip to embed at the top of the description.
2. **turn-around.mp4** — spin the camera in a detailed area; shows no stutter or popping (fade-in).
3. **vram-graph.mp4** (optional) — Task Manager GPU memory graph side by side while flying around: flat line.

## Also needed
- **icon.png** — a placeholder is in `src/main/resources/assets/spritestream/icon.png` (128x128). Replace it
  with your own if you prefer; Modrinth wants at least 96x96, square.
