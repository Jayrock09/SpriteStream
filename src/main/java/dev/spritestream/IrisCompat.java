package dev.spritestream;

import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/** Which Iris is installed, and whether our shader-pack hooks support it. Safe to call before mods initialise. */
public final class IrisCompat {
	/** The hooks use Iris 1.12's sampler and shader-transform API, which 1.11 does not have. */
	private static final String MIN_VERSION = "1.12.0-";

	private IrisCompat() {
	}

	public static boolean isInstalled() {
		return FabricLoader.getInstance().isModLoaded("iris");
	}

	/** True when Iris is installed and new enough for our hooks. */
	public static boolean isSupported() {
		Optional<ModContainer> iris = FabricLoader.getInstance().getModContainer("iris");
		if (iris.isEmpty()) {
			return false;
		}
		Version installed = iris.get().getMetadata().getVersion();
		try {
			return installed.compareTo(SemanticVersion.parse(MIN_VERSION)) >= 0;
		} catch (VersionParsingException e) {
			return false;
		}
	}

	/** True when this jar was built with the Iris hooks included. */
	public static boolean hooksPresent() {
		return IrisCompat.class.getClassLoader().getResource("dev/spritestream/mixin/iris/IrisSamplersMixin.class") != null;
	}

	public static String installedVersion() {
		return FabricLoader.getInstance().getModContainer("iris").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("none");
	}
}
