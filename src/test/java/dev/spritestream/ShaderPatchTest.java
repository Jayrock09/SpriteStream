package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

/** Rewriting of shader-pack terrain fragment shaders, modelled on Bliss and Alpha Piscium. */
class ShaderPatchTest {
	/** Bliss-style: a helper that takes the atlas as a parameter, plus direct and nested reads. */
	private static final String PACK = """
		#version 460 core
		uniform sampler2D gtexture;
		uniform sampler2D normals;
		uniform sampler2D specular;
		layout(location = 0) in vec2 uv;
		layout(location = 0) out vec4 outColor;

		vec4 texture2D_POMSwitch(sampler2D tex, vec2 coord, vec4 d, bool pom, float lod) {
		    if (pom) {
		        return textureGrad(tex, coord, d.xy, d.zw);
		    }
		    return texture(tex, coord, lod);
		}

		void main() {
		    vec4 albedo = texture2D_POMSwitch(gtexture, uv, vec4(dFdx(uv), dFdy(uv)), true, 0.0);
		    vec4 normal = texture2D_POMSwitch(normals, uv, vec4(dFdx(uv), dFdy(uv)), false, 0.0);
		    vec4 height = textureGrad(normals, fract(uv) * 0.5 + texture(gtexture, uv).xy, dFdx(uv), dFdy(uv));
		    float emission = textureLod(specular, uv, 0.0).a;
		    outColor = albedo + normal + height + vec4(emission);
		}
		""";

	/** The rewrite may leave a space after an opening parenthesis; tests compare without it. */
	private static String patched() {
		return ShaderPatch.patchIrisFragment("test", PACK).replaceAll("\\(\\s+", "(");
	}

	@Test
	void helperCallsGetSpecialisedCopies() {
		String out = patched();
		assertTrue(out.contains("texture2D_POMSwitch__ss_c(uv,"), out);
		assertTrue(out.contains("texture2D_POMSwitch__ss_n(uv,"), out);
		assertTrue(out.contains("vec4 texture2D_POMSwitch__ss_c(vec2 coord,"), out);
		// Inside the copies the parameter is the real sampler, so its reads are rewritten too.
		assertTrue(out.contains("ss_grad_c(coord, d.xy, d.zw)"), out);
		assertTrue(out.contains("ss_tex_n(coord, lod)"), out);
	}

	@Test
	void directAndNestedReadsAreRewritten() {
		String out = patched();
		assertTrue(out.contains("ss_grad_n(fract(uv) * 0.5 + ss_tex_c(uv).xy, dFdx(uv), dFdy(uv))"), out);
		assertTrue(out.contains("ss_lod_s(uv, 0.0)"), out);
		// No pack read of an atlas is left outside our own library functions.
		String packPart = out.substring(out.indexOf("void main()"));
		assertFalse(packPart.matches("(?s).*\\b(texture|textureLod|textureGrad)\\s*\\(\\s*(gtexture|normals|specular)\\s*,.*"), packPart);
	}

	@Test
	void libraryGoesAfterTheVersionLine() {
		String out = ShaderPatch.patchIrisFragment("test", PACK);
		assertTrue(out.startsWith("#version 460 core\n"), out);
		assertTrue(out.indexOf("uniform sampler2D ss_IdMap;") < out.indexOf("void main()"));
		// Bodies come after the pack's sampler declarations, which they read.
		assertTrue(out.indexOf("vec4 ss_grad_c(vec2 uv, vec2 dx, vec2 dy) {") > out.indexOf("uniform sampler2D specular;"));
	}

	@Test
	void patchingIsIdempotent() {
		String once = ShaderPatch.patchIrisFragment("test", PACK);
		assertEquals(once, ShaderPatch.patchIrisFragment("test", once));
	}

	@Test
	void shadersWithoutAtlasReadsAreLeftAlone() {
		String plain = "#version 460 core\nuniform sampler2D noisetex;\nvoid main() { vec4 n = texture(noisetex, vec2(0.0)); }\n";
		assertEquals(plain, ShaderPatch.patchIrisFragment("test", plain));
	}

	@Test
	void oldGlslIsLeftAlone() {
		String old = "#version 120\nuniform sampler2D gtexture;\nvoid main() { gl_FragColor = texture2D(gtexture, vec2(0.0)); }\n";
		assertEquals(old, ShaderPatch.patchIrisFragment("test", old));
	}

	@Test
	void sodiumTerrainShaderIsRecognised() throws java.io.IOException {
		String sodium;
		try (var in = ShaderPatchTest.class.getClassLoader().getResourceAsStream("assets/sodium/shaders/blocks/block_layer_opaque.fsh")) {
			Assumptions.assumeTrue(in != null, "Sodium not on the test classpath");
			sodium = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		String out = ShaderPatch.patchSodiumFragment(sodium);
		assertTrue(out.contains("vec4 color = ss_apply("), out);
		assertTrue(out.indexOf("uniform sampler2D ss_IdMap;") > out.indexOf("uniform sampler2D u_BlockTex;"), out);
		assertEquals(out, ShaderPatch.patchSodiumFragment(out));
	}

	/** The patched shader must still compile; uses the shaderc that ships with Minecraft. */
	@Test
	void patchedShaderCompiles() {
		long compiler;
		try {
			compiler = Shaderc.shaderc_compiler_initialize();
		} catch (Throwable e) {
			Assumptions.abort("shaderc natives not available in the test JVM: " + e);
			return;
		}
		long options = Shaderc.shaderc_compile_options_initialize();
		Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
		Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
		ByteBuffer source = MemoryUtil.memUTF8(ShaderPatch.patchIrisFragment("test", PACK), false);
		ByteBuffer name = MemoryUtil.memUTF8("test.fsh");
		ByteBuffer entry = MemoryUtil.memUTF8("main");
		long result = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_fragment_shader, name, entry, options);
		try {
			assertEquals(Shaderc.shaderc_compilation_status_success, Shaderc.shaderc_result_get_compilation_status(result),
				Shaderc.shaderc_result_get_error_message(result));
		} finally {
			Shaderc.shaderc_result_release(result);
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
			MemoryUtil.memFree(source);
			MemoryUtil.memFree(name);
			MemoryUtil.memFree(entry);
		}
	}
}
