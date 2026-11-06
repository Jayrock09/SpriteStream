package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class IrisHookCheckTest {
	private static final ClassLoader LOADER = IrisHookCheckTest.class.getClassLoader();

	@Test
	void theIrisWeBuildAgainstHasEverythingTheHooksNeed() {
		Assumptions.assumeTrue(
			LOADER.getResource("net/irisshaders/iris/samplers/IrisSamplers.class") != null, "no Iris jar configured (irisJar)"
		);
		assertEquals(List.of(), IrisHookCheck.missing(LOADER));
	}

	@Test
	void missingClassesAreReported() {
		List<String> missing = IrisHookCheck.missing(name -> null);
		assertTrue(missing.size() >= 10, missing.toString());
		assertTrue(missing.stream().allMatch(m -> m.endsWith("(class not found)")), missing.toString());
	}

	@Test
	void aChangedMethodIsReported() {
		Assumptions.assumeTrue(LOADER.getResource("net/irisshaders/iris/samplers/IrisSamplers.class") != null, "no Iris jar configured");
		// Pretend TransformPatcher is some other class: patchSodium is then missing, everything else is still found.
		List<String> missing = IrisHookCheck.missing(name -> {
			String lookup = name.equals("net/irisshaders/iris/pipeline/transform/TransformPatcher")
				? "net/irisshaders/iris/gl/sampler/GlSampler" : name;
			try (var in = LOADER.getResourceAsStream(lookup + ".class")) {
				return in == null ? null : in.readAllBytes();
			} catch (java.io.IOException e) {
				return null;
			}
		});
		assertEquals(1, missing.size(), missing.toString());
		assertTrue(missing.get(0).startsWith("TransformPatcher.patchSodium"), missing.toString());
	}
}
