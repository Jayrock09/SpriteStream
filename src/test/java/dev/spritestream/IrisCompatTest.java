package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.spritestream.IrisHookCheck.HookSet;
import java.util.List;
import net.fabricmc.loader.api.Version;
import org.junit.jupiter.api.Test;

/** Which Iris versions stream, with which hooks, and when Iris can run shader packs at all. */
class IrisCompatTest {
	private static final IrisHookCheck.Result MODERN = new IrisHookCheck.Result(HookSet.TEXTURE_BINDING, List.of());
	private static final IrisHookCheck.Result GL = new IrisHookCheck.Result(HookSet.GL_TEXTURE_IDS, List.of());
	private static final IrisHookCheck.Result BROKEN = new IrisHookCheck.Result(HookSet.NONE, List.of("TransformPatcher.patchSodium(...)"));

	private static IrisCompat.Decision decide(final String version, final IrisHookCheck.Result check) throws Exception {
		return IrisCompat.decide(Version.parse(version), true, check);
	}

	@Test
	void noIrisStreamsWithoutHooks() {
		IrisCompat.Decision decision = IrisCompat.decide(null, true, new IrisHookCheck.Result(HookSet.NONE, List.of()));
		assertTrue(decision.stream());
		assertEquals(HookSet.NONE, decision.hooks());
		assertNull(decision.whyOff());
	}

	@Test
	void iris112UsesTextureViews() throws Exception {
		IrisCompat.Decision decision = decide("1.12.0-snapshot+mc26.3-local", MODERN);
		assertTrue(decision.stream());
		assertEquals(HookSet.TEXTURE_BINDING, decision.hooks());
	}

	@Test
	void iris111UsesOpenGlNames() throws Exception {
		for (String version : List.of("1.11.0+mc26.3", "1.11.5+mc26.3", "1.11.7+mc26.3")) {
			IrisCompat.Decision decision = decide(version, GL);
			assertTrue(decision.stream(), version);
			assertEquals(HookSet.GL_TEXTURE_IDS, decision.hooks(), version);
		}
	}

	@Test
	void olderIrisIsOff() throws Exception {
		IrisCompat.Decision decision = decide("1.10.4+mc26.3", GL);
		assertFalse(decision.stream());
		assertTrue(decision.whyOff().contains("older than 1.11"), decision.whyOff());
	}

	@Test
	void anyIrisMissingHookedCodeIsOff() throws Exception {
		for (String version : List.of("1.11.7+mc26.3", "1.12.0+mc26.3", "1.13.0+mc26.4", "2.0.0")) {
			IrisCompat.Decision decision = decide(version, BROKEN);
			assertFalse(decision.stream(), version);
			assertTrue(decision.whyOff().contains("TransformPatcher.patchSodium"), decision.whyOff());
		}
	}

	@Test
	void futureIrisWithOnlyTheOldSamplerApiIsOff() throws Exception {
		// A 1.12+ Iris may run shader packs on Vulkan, where OpenGL texture names mean nothing.
		IrisCompat.Decision decision = decide("1.13.0+mc26.4", GL);
		assertFalse(decision.stream());
	}

	@Test
	void futureIrisWithTheCurrentApiStreams() throws Exception {
		assertTrue(decide("1.13.0+mc26.4", MODERN).stream());
	}

	@Test
	void jarWithoutHooksIsOffWhenIrisIsInstalled() throws Exception {
		IrisCompat.Decision decision = IrisCompat.decide(Version.parse("1.12.0+mc26.3"), false, MODERN);
		assertFalse(decision.stream());
	}

	@Test
	void shaderPacksPossibleByBackend() {
		assertTrue(IrisCompat.shaderPacksPossible(HookSet.TEXTURE_BINDING, "Vulkan"));
		assertTrue(IrisCompat.shaderPacksPossible(HookSet.TEXTURE_BINDING, "OpenGL"));
		// Iris 1.11 shows only a "switch to OpenGL" screen on Vulkan.
		assertFalse(IrisCompat.shaderPacksPossible(HookSet.GL_TEXTURE_IDS, "Vulkan"));
		assertTrue(IrisCompat.shaderPacksPossible(HookSet.GL_TEXTURE_IDS, "OpenGL"));
		assertFalse(IrisCompat.shaderPacksPossible(HookSet.GL_TEXTURE_IDS, null));
		assertFalse(IrisCompat.shaderPacksPossible(HookSet.NONE, "OpenGL"));
	}
}
