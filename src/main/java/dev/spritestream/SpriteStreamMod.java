package dev.spritestream;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class SpriteStreamMod implements ClientModInitializer {
	public static final Logger LOGGER = LogUtils.getLogger();
	/** {@code -Dspritestream.disabled=true} leaves every atlas untouched; used for baseline measurements. */
	public static final boolean DISABLED = Boolean.getBoolean("spritestream.disabled");
	public static SpriteStreamConfig config = new SpriteStreamConfig();

	private static @Nullable Object irisApi;
	private static @Nullable Method irisPackInUse;
	private static boolean irisLookupDone;

	@Override
	public void onInitializeClient() {
		config = SpriteStreamConfig.load();
		LOGGER.info(
			"[SpriteStream] base {}x, max {}x, pool budget {} MiB",
			config.baseResolution,
			config.maxResolution,
			config.vramBudgetMiB
		);
	}

	/**
	 * Streaming needs a terrain shader we can patch: Sodium's own, or an Iris shader pack's terrain programs
	 * (Iris builds those on top of Sodium too).
	 */
	public static boolean shouldStream() {
		if (DISABLED || !FabricLoader.getInstance().isModLoaded("sodium")) {
			return false;
		}
		// With an Iris we cannot hook, a shader pack would show only the small base textures, so stay off.
		if (IrisCompat.isInstalled() && !(IrisCompat.isSupported() && IrisCompat.hooksPresent())) {
			LOGGER.warn(
				"[SpriteStream] Iris {} is not supported by this build (needs Iris 1.12+ and a build with the Iris hooks); streaming is off",
				IrisCompat.installedVersion()
			);
			return false;
		}
		return true;
	}

	/** True while an Iris shader pack is rendering, which is when normal and specular maps are read. */
	public static boolean isIrisPackActive() {
		if (!irisLookupDone) {
			irisLookupDone = true;
			if (FabricLoader.getInstance().isModLoaded("iris")) {
				try {
					Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
					irisApi = api.getMethod("getInstance").invoke(null);
					irisPackInUse = api.getMethod("isShaderPackInUse");
				} catch (ReflectiveOperationException | LinkageError e) {
					LOGGER.warn("[SpriteStream] Could not reach the Iris API; PBR maps will not stream", e);
				}
			}
		}
		if (irisPackInUse == null) {
			return false;
		}
		try {
			return (boolean) irisPackInUse.invoke(irisApi);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}
}
