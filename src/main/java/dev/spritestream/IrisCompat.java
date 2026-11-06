package dev.spritestream;

import dev.spritestream.IrisHookCheck.HookSet;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.jspecify.annotations.Nullable;

/**
 * Which Iris is installed and which of our hook sets fits it. Safe to call before mods initialise.
 * <ul>
 * <li>Iris 1.12+: samplers are bound as texture views; shader packs run on OpenGL and Vulkan.</li>
 * <li>Iris 1.11: samplers are bound as OpenGL texture names. Iris 1.11 runs shader packs on OpenGL only; on
 * Vulkan it loads nothing but a "switch to OpenGL" screen, so there SpriteStream behaves as without Iris.</li>
 * </ul>
 */
public final class IrisCompat {
	static final String MIN_VERSION = "1.11.0-";
	/** First Iris line with texture-view samplers (and shader packs on Vulkan). */
	static final String TEXTURE_BINDING_VERSION = "1.12.0-";

	private static IrisHookCheck.@Nullable Result checked;

	private IrisCompat() {
	}

	/** What to do with a given Iris. */
	public record Decision(boolean stream, HookSet hooks, @Nullable String whyOff) {
		static Decision off(final String why) {
			return new Decision(false, HookSet.NONE, why);
		}
	}

	/**
	 * The pure rules, for tests: {@code version} is the installed Iris (null when absent), {@code check} what the
	 * class-file check found.
	 */
	static Decision decide(final @Nullable Version version, final boolean hooksInJar, final IrisHookCheck.Result check) {
		if (version == null) {
			return new Decision(true, HookSet.NONE, null);
		}
		String friendly = version.getFriendlyString();
		if (!atLeast(version, MIN_VERSION)) {
			return Decision.off("Iris " + friendly + " is older than 1.11, which the shader-pack support needs");
		}
		if (!hooksInJar) {
			return Decision.off("this SpriteStream build was made without the Iris hooks");
		}
		if (check.set() == HookSet.NONE) {
			return Decision.off("Iris " + friendly + " changed code SpriteStream hooks into (" + String.join(", ", check.missing()) + ")");
		}
		// The OpenGL-name hooks are only right for the 1.11 line, whose shader packs never run on Vulkan.
		if (check.set() == HookSet.GL_TEXTURE_IDS && atLeast(version, TEXTURE_BINDING_VERSION)) {
			return Decision.off("Iris " + friendly + " is newer than 1.11 but lacks the 1.12 sampler API SpriteStream uses");
		}
		return new Decision(true, check.set(), null);
	}

	/**
	 * Whether Iris can run a shader pack on this backend at all. With the 1.11 hooks that is OpenGL only;
	 * {@code backendName} is the render device's backend ("OpenGL", "Vulkan").
	 */
	static boolean shaderPacksPossible(final HookSet hooks, final @Nullable String backendName) {
		return switch (hooks) {
			case TEXTURE_BINDING -> true;
			case GL_TEXTURE_IDS -> backendName != null && backendName.toLowerCase(java.util.Locale.ROOT).contains("opengl");
			case NONE -> false;
		};
	}

	// ---------------------------------------------------------------- live game

	public static Decision decision() {
		return decide(installedVersionOrNull(), hooksPresent(), check());
	}

	/** Mixins for the chosen hook set; empty when Iris is absent or unsupported. */
	public static List<String> mixins() {
		return decision().hooks().mixins();
	}

	/** True when the Iris hooks are applied. */
	public static boolean hooksUsable() {
		return decision().hooks() != HookSet.NONE;
	}

	/**
	 * True when Iris could render a shader pack in this session (so its PBR maps are worth preparing). Only call
	 * once the render device exists, never during mixin setup.
	 */
	public static boolean shaderPacksPossible() {
		return shaderPacksPossible(decision().hooks(), RenderBackend.name());
	}

	/** Why streaming is off because of Iris, for the log; null when Iris is absent or supported. */
	public static @Nullable String whyUnusable() {
		return decision().whyOff();
	}

	private static synchronized IrisHookCheck.Result check() {
		if (checked == null) {
			checked = isInstalled() ? IrisHookCheck.choose(IrisCompat.class.getClassLoader()) : new IrisHookCheck.Result(HookSet.NONE, List.of());
		}
		return checked;
	}

	public static boolean isInstalled() {
		return FabricLoader.getInstance().isModLoaded("iris");
	}

	/** True when this jar was built with the Iris hooks included. */
	public static boolean hooksPresent() {
		return IrisCompat.class.getClassLoader().getResource("dev/spritestream/mixin/iris/TransformPatcherMixin.class") != null;
	}

	public static String installedVersion() {
		Version version = installedVersionOrNull();
		return version == null ? "none" : version.getFriendlyString();
	}

	private static @Nullable Version installedVersionOrNull() {
		return FabricLoader.getInstance().getModContainer("iris").map(ModContainer::getMetadata).map(m -> m.getVersion()).orElse(null);
	}

	private static boolean atLeast(final Version version, final String minimum) {
		try {
			return version.compareTo(SemanticVersion.parse(minimum)) >= 0;
		} catch (VersionParsingException | RuntimeException e) {
			return false;
		}
	}
}
