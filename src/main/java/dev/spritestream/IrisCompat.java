package dev.spritestream;

import java.util.List;
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

	private static @org.jspecify.annotations.Nullable List<String> missingMembers;

	private IrisCompat() {
	}

	/** True when the Iris hooks can be applied: a supported Iris, a build that has them, and every hooked member present. */
	public static boolean hooksUsable() {
		return isSupported() && hooksPresent() && missingMembers().isEmpty();
	}

	/** Members the hooks need that the installed Iris lacks (checked once, from class files, without loading them). */
	public static synchronized List<String> missingMembers() {
		if (missingMembers == null) {
			missingMembers = isInstalled() ? IrisHookCheck.missing(IrisCompat.class.getClassLoader()) : List.of();
		}
		return missingMembers;
	}

	/** Why the hooks are off, for the log; null when they are on or Iris is absent. */
	public static @org.jspecify.annotations.Nullable String whyUnusable() {
		if (!isInstalled()) {
			return null;
		}
		if (!isSupported()) {
			return "Iris " + installedVersion() + " is older than 1.12, which the shader-pack support needs";
		}
		if (!hooksPresent()) {
			return "this SpriteStream build was made without the Iris hooks";
		}
		if (!missingMembers().isEmpty()) {
			return "Iris " + installedVersion() + " changed code SpriteStream hooks into (" + String.join(", ", missingMembers()) + ")";
		}
		return null;
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
