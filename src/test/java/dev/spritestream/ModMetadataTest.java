package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;

/** The dependency ranges in fabric.mod.json parse, and accept the versions SpriteStream was checked against. */
class ModMetadataTest {
	private static JsonObject metadata() throws Exception {
		try (var in = ModMetadataTest.class.getClassLoader().getResourceAsStream("fabric.mod.json")) {
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static boolean accepts(final JsonObject section, final String mod, final String version) throws Exception {
		VersionPredicate predicate = VersionPredicate.parse(section.get(mod).getAsString());
		return predicate.test(Version.parse(version));
	}

	@Test
	void dependencyRangesAcceptTestedVersions() throws Exception {
		JsonObject depends = metadata().getAsJsonObject("depends");
		assertTrue(accepts(depends, "minecraft", "26.3"));
		assertTrue(accepts(depends, "minecraft", "26.3.1"));
		assertFalse(accepts(depends, "minecraft", "26.4"));
		assertTrue(accepts(depends, "sodium", "0.9.3-beta.1+mc26.3"));
		assertTrue(accepts(depends, "sodium", "0.9.2+mc26.3"));
		assertFalse(accepts(depends, "sodium", "0.9.1+mc26.3"));
		assertFalse(accepts(depends, "sodium", "0.10.0-alpha.1+mc26.3"));
		assertTrue(accepts(depends, "fabricloader", "0.19.5"));
		JsonObject suggests = metadata().getAsJsonObject("suggests");
		assertTrue(accepts(suggests, "iris", "1.12.0-snapshot+mc26.3-local"));
		assertTrue(accepts(suggests, "iris", "1.11.7+mc26.3"));
		assertFalse(accepts(suggests, "iris", "1.10.4+mc26.3"));
	}

	@Test
	void irisCompatMinimumMatchesTheSuggestion() throws Exception {
		VersionPredicate suggested = VersionPredicate.parse(metadata().getAsJsonObject("suggests").get("iris").getAsString());
		Version minimum = SemanticVersion.parse(IrisCompat.MIN_VERSION);
		assertTrue(suggested.test(minimum), "fabric.mod.json and IrisCompat.MIN_VERSION disagree");
		assertTrue(Version.parse("1.11.0+mc26.3").compareTo(minimum) >= 0);
	}

	@Test
	void identity() throws Exception {
		JsonObject json = metadata();
		assertEquals("spritestream", json.get("id").getAsString());
		assertEquals("LGPL-3.0-only", json.get("license").getAsString());
		assertTrue(ModMetadataTest.class.getClassLoader().getResource(json.get("icon").getAsString()) != null, "icon missing");
	}
}
