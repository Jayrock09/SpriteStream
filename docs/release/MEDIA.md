# Screenshots and videos needed (Jayden)

## CurseForge (current target)

**Avatar:** `docs/release/curseforge-avatar.png`, 400x400 PNG made from the mod icon (not WebP, not a solid
colour). Swap it for your own if you like.

**Gallery: unedited in-game captures** (2560x1440, straight from the game's screenshot function; not cropped,
retouched or AI-processed). They are in `docs/release/curseforge-gallery/` on this PC (git-ignored, about
9 MB each). All come from the test client on 6 Nov 2026: Optimum Realism 1024x, Bliss (Unstable), OpenGL,
RX 9070, a fixed-seed world. The spectator crosshair (and in 05 the hotbar and spyglass overlay) is visible
because nothing was edited.

| File | Shows | Source run |
|---|---|---|
| `01-closeup-1024x-bliss.png` | jungle log and brick wall up close at 1024x with Bliss POM (suggested cover image) | `run/spritestream-bench/pfl-run2/closeup.png` |
| `02-village-bliss.png` | a village from above | `pfl-run2/village.png` |
| `03-forest-bliss.png` | forest edge, water and cliffs | `pfl-run2/forest.png` |
| `04-connected-textures-bliss.png` | connected bookshelves and sandstone (Continuity), streamed at 512 | `cl-extras2/ctm.png` |
| `05-spyglass-zoom-bliss.png` | a wall 48 blocks away through the spyglass, streamed sharp | `tb-run1/zoom-on.png` |
| `06-closeup-no-shaders.png` | the close-up wall without a shader pack | `cl-sharp-new/closeup.png` |
| `07-emissive-night-bliss.png` | emissive and metal blocks at night | `cl-mat-new/night-near.png` — **hold until you decide on the emission look** |

The before/after comparisons in `docs/perf/img/` are labelled side-by-side composites. They are fine to
link from the description as comparisons, but they are not single unedited captures, so they are not
listed for the gallery.

If CurseForge rejects a file for its size, export the same image as a high-quality JPEG (re-encoding only, no
other changes).

## Still needed from you

Optional extra media (any platform). The first gallery image matters most. Use 1920x1080, hide the
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
