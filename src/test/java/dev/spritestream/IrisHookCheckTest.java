package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.spritestream.IrisHookCheck.HookSet;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Runs the hook pre-flight against the real Iris jars (paths passed in by Gradle). */
class IrisHookCheckTest {
	/** Class files read straight from a jar, so both Iris versions can be checked in one test run. */
	static Function<String, byte @Nullable []> jar(final String property) {
		String path = System.getProperty(property);
		Assumptions.assumeTrue(path != null, property + " not set (no such Iris jar configured)");
		return name -> {
			try (ZipFile zip = new ZipFile(path)) {
				var entry = zip.getEntry(name + ".class");
				if (entry == null) {
					return null;
				}
				try (var in = zip.getInputStream(entry)) {
					return in.readAllBytes();
				}
			} catch (IOException e) {
				return null;
			}
		};
	}

	@Test
	void iris112GetsTheTextureViewHooks() {
		IrisHookCheck.Result result = IrisHookCheck.choose(jar("spritestream.iris112Jar"));
		assertEquals(HookSet.TEXTURE_BINDING, result.set(), result.missing().toString());
	}

	@Test
	void iris111GetsTheOpenGlNameHooks() {
		IrisHookCheck.Result result = IrisHookCheck.choose(jar("spritestream.iris111Jar"));
		assertEquals(HookSet.GL_TEXTURE_IDS, result.set(), result.missing().toString());
	}

	@Test
	void noIrisClassesMeansNoHooks() {
		IrisHookCheck.Result result = IrisHookCheck.choose(name -> null);
		assertEquals(HookSet.NONE, result.set());
		assertTrue(result.missing().size() >= 8, result.missing().toString());
		assertTrue(result.missing().stream().allMatch(m -> m.endsWith("(class not found)")), result.missing().toString());
	}

	@Test
	void aChangedSharedMethodTurnsEverythingOff() {
		Function<String, byte @Nullable []> iris = jar("spritestream.iris112Jar");
		// Pretend TransformPatcher is some other class: patchSodium is then missing.
		IrisHookCheck.Result result = IrisHookCheck.choose(
			name -> iris.apply(name.equals("net/irisshaders/iris/pipeline/transform/TransformPatcher") ? "net/irisshaders/iris/gl/sampler/GlSampler" : name)
		);
		assertEquals(HookSet.NONE, result.set());
		assertEquals(1, result.missing().size(), result.missing().toString());
		assertTrue(result.missing().get(0).startsWith("TransformPatcher.patchSodium"), result.missing().toString());
	}

	@Test
	void theMixinListMatchesTheMixinClasses() {
		for (HookSet set : List.of(HookSet.TEXTURE_BINDING, HookSet.GL_TEXTURE_IDS)) {
			for (String mixin : set.mixins()) {
				String resource = "dev/spritestream/mixin/" + mixin.replace('.', '/') + ".class";
				Assumptions.assumeTrue(System.getProperty("spritestream.iris112Jar") != null, "built without Iris hooks");
				assertTrue(IrisHookCheckTest.class.getClassLoader().getResource(resource) != null, resource);
			}
		}
		assertEquals(List.of(), HookSet.NONE.mixins());
	}
}
