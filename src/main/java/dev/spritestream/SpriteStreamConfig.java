package dev.spritestream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** User settings, stored in config/spritestream.json. */
public final class SpriteStreamConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int CURRENT_VERSION = 2;

	/** Bumped when a default changes, so files written with the old default pick up the new one. */
	public int configVersion;

	/** Resolution every block texture is stored at in the always-resident atlas. */
	public int baseResolution = 64;
	/** Animated textures are not streamed, so they are capped here in the atlas instead. */
	public int animatedResolution = 256;
	/** Cap for the non-streamed atlases (items, particles, chests, ...); 0 leaves them untouched. GUI is never capped. */
	public int otherAtlasResolution = 256;
	/** Highest resolution the pool will hold (128 … 4096). Sources above this are downscaled. */
	public int maxResolution = 2048;
	/** Total VRAM for the high-res pool, in MiB. The pool is allocated once, so it can never exceed this. */
	public int vramBudgetMiB = 2560;
	/** Texels per screen pixel to aim for. 1.0 = one texel per pixel; lower trades sharpness for memory. */
	public float qualityBias = 1.0F;
	/** Screen probe grid. More rays catch smaller objects; cost is on the render thread. */
	public int probeColumns = 64;
	public int probeRows = 36;
	/** Upload budget per frame, in MiB, to avoid stutter. */
	public int uploadMiBPerFrame = 24;
	/** Seconds a texture stays at a tier after it stops being wanted there. */
	public float evictDelaySeconds = 3.0F;
	/** Fade between resolutions over this many seconds so upgrades do not pop. */
	public float fadeSeconds = 0.25F;
	/** Background decode threads. */
	public int decodeThreads = 3;
	/**
	 * With Iris installed, shrink the {@code _n}/{@code _s} maps while the resource pack loads, so turning a
	 * shader pack on does not stall the game while Iris builds its PBR atlases.
	 */
	public boolean preparePbrMapsAtLoad = true;
	/** Write a status line to the log every few seconds. */
	public boolean logStats = true;

	public static SpriteStreamConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("spritestream.json");
		SpriteStreamConfig config = new SpriteStreamConfig();
		config.configVersion = CURRENT_VERSION;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				SpriteStreamConfig read = GSON.fromJson(reader, SpriteStreamConfig.class);
				if (read != null) {
					config = read;
					config.migrate();
				}
			} catch (Exception e) {
				SpriteStreamMod.LOGGER.warn("Could not read {}, using defaults", path, e);
			}
		}
		config.sanitize();
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(config, writer);
		} catch (IOException e) {
			SpriteStreamMod.LOGGER.warn("Could not write {}", path, e);
		}
		return config;
	}

	/** Moves values still at an old default to the current default. */
	private void migrate() {
		// Files from before configVersion existed parse it as 0.
		if (this.configVersion < 2 && this.animatedResolution == 128) {
			this.animatedResolution = 256;
		}
		this.configVersion = CURRENT_VERSION;
	}

	private void sanitize() {
		this.baseResolution = Integer.highestOneBit(Math.clamp(this.baseResolution, 16, 256));
		this.animatedResolution = Integer.highestOneBit(Math.clamp(this.animatedResolution, 16, 1024));
		this.otherAtlasResolution = this.otherAtlasResolution <= 0 ? 0 : Integer.highestOneBit(Math.clamp(this.otherAtlasResolution, 16, 4096));
		this.maxResolution = Integer.highestOneBit(Math.clamp(this.maxResolution, this.baseResolution * 2, 4096));
		this.vramBudgetMiB = Math.clamp(this.vramBudgetMiB, 128, 65536);
		this.qualityBias = Math.clamp(this.qualityBias, 0.1F, 4.0F);
		this.probeColumns = Math.clamp(this.probeColumns, 8, 256);
		this.probeRows = Math.clamp(this.probeRows, 8, 256);
		this.uploadMiBPerFrame = Math.clamp(this.uploadMiBPerFrame, 1, 512);
		this.evictDelaySeconds = Math.clamp(this.evictDelaySeconds, 0.0F, 60.0F);
		this.fadeSeconds = Math.clamp(this.fadeSeconds, 0.0F, 5.0F);
		this.decodeThreads = Math.clamp(this.decodeThreads, 1, 16);
	}
}
