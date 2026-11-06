package dev.spritestream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * Runs each Iris version's own terrain-shader transform (the step our TransformPatcher hook post-processes) on a
 * shader-pack program, applies our patch to the result, and compiles it: for Iris 1.11 as OpenGL GLSL (the only
 * backend 1.11 runs shader packs on), for Iris 1.12 for Vulkan.
 */
class IrisTransformTest {
	private static final String VERTEX = """
		#version 330 compatibility
		out vec2 texcoord;
		out vec4 glcolor;
		void main() {
		    gl_Position = ftransform();
		    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
		    glcolor = gl_Color;
		}
		""";

	/** Bliss-style: reads through a helper that takes the atlas as a parameter, POM-style gradient reads, LabPBR. */
	private static final String FRAGMENT = """
		#version 330 compatibility
		uniform sampler2D gtexture;
		uniform sampler2D normals;
		uniform sampler2D specular;
		in vec2 texcoord;
		in vec4 glcolor;
		/* RENDERTARGETS: 0,1,2 */
		layout(location = 0) out vec4 color;
		layout(location = 1) out vec4 normalOut;
		layout(location = 2) out vec4 specularOut;

		vec4 texture2D_POMSwitch(sampler2D tex, vec2 coord, vec4 d, bool pom) {
		    if (pom) {
		        return textureGrad(tex, coord, d.xy, d.zw);
		    }
		    return texture(tex, coord);
		}

		void main() {
		    vec4 d = vec4(dFdx(texcoord), dFdy(texcoord));
		    color = texture2D_POMSwitch(gtexture, texcoord, d, true) * glcolor;
		    if (color.a < 0.1) discard;
		    float height = textureGrad(normals, texcoord, d.xy, d.zw).a;
		    normalOut = vec4(texture2D_POMSwitch(normals, texcoord, d, false).rgb, height);
		    specularOut = textureLod(specular, texcoord, 0.0);
		}
		""";

	private static final String ATLAS_READ = "(?s).*\\b(texture|textureLod|textureGrad)\\s*\\(\\s*(gtexture|normals|specular)\\s*,.*";

	@Test
	void iris111TransformThenOurPatchCompilesForOpenGl() throws Exception {
		String patched = checkPatched(transformWith("spritestream.iris111Jar", false));
		compile(patched, Shaderc.shaderc_target_env_opengl, Shaderc.shaderc_env_version_opengl_4_5);
	}

	@Test
	void iris112TransformThenOurPatchCompilesForVulkan() throws Exception {
		String patched = checkPatched(transformWith("spritestream.iris112Jar", true));
		compile(patched, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
	}

	/** Our patch found every atlas read Iris left in the program and rewrote it. */
	private static String checkPatched(final String irisFragment) {
		String patched = ShaderPatch.patchIrisFragment("gbuffers_terrain", irisFragment);
		assertTrue(patched.contains("ss_resolve("), "not patched:\n" + irisFragment);
		String body = patched.substring(patched.lastIndexOf("void main()"));
		assertFalse(body.matches(ATLAS_READ), "unrewritten atlas read left in:\n" + body);
		return patched;
	}

	/** Iris's Sodium terrain transform from the given jar, isolated from the other Iris version. */
	private static String transformWith(final String jarProperty, final boolean vulkanDepth) throws Exception {
		String jar = System.getProperty(jarProperty);
		Assumptions.assumeTrue(jar != null, jarProperty + " not set");
		Field deviceField = com.mojang.blaze3d.systems.RenderSystem.class.getDeclaredField("DEVICE");
		deviceField.setAccessible(true);
		deviceField.set(null, fakeDevice(vulkanDepth));
		// Iris 1.11's static setup asks Fabric Loader whether this is a development environment.
		Field launcherField = net.fabricmc.loader.impl.launch.FabricLauncherBase.class.getDeclaredField("launcher");
		launcherField.setAccessible(true);
		Object previousLauncher = launcherField.get(null);
		if (previousLauncher == null) {
			launcherField.set(null, java.lang.reflect.Proxy.newProxyInstance(
				IrisTransformTest.class.getClassLoader(),
				new Class<?>[] {net.fabricmc.loader.impl.launch.FabricLauncher.class},
				(proxy, method, args) -> {
					if (method.getName().equals("isDevelopment")) {
						return false;
					}
					throw new UnsupportedOperationException("test launcher: " + method.getName());
				}
			));
		}
		Thread thread = Thread.currentThread();
		ClassLoader previousContext = thread.getContextClassLoader();
		try (URLClassLoader loader = isolatedIris(Path.of(jar))) {
			// Iris looks up its platform helpers through the context class loader.
			thread.setContextClassLoader(loader);
			Class<?> iris = loader.loadClass("net.irisshaders.iris.Iris");
			Class<?> configClass = loader.loadClass("net.irisshaders.iris.config.IrisConfig");
			Path tmp = Files.createTempDirectory("iris-config");
			Object config = configClass.getConstructor(Path.class, Path.class)
				.newInstance(tmp.resolve("iris.properties"), tmp.resolve("excluded.json"));
			Field field = iris.getDeclaredField("irisConfig");
			field.setAccessible(true);
			field.set(null, config);

			Class<?> alphaTest = loader.loadClass("net.irisshaders.iris.gl.blending.AlphaTest");
			Object alwaysPass = alphaTest.getField("ALWAYS").get(null);
			Class<?> patcher = loader.loadClass("net.irisshaders.iris.pipeline.transform.TransformPatcher");
			Method patchSodium = patcher.getMethod(
				"patchSodium", String.class, String.class, String.class, String.class, String.class, String.class, alphaTest,
				it.unimi.dsi.fastutil.objects.Object2ObjectMap.class, java.util.Set.class, boolean.class
			);
			Map<?, ?> result = (Map<?, ?>) patchSodium.invoke(
				null, "gbuffers_terrain", VERTEX, null, null, null, FRAGMENT, alwaysPass,
				new it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap<>(), Collections.emptySet(), false
			);
			for (Map.Entry<?, ?> entry : result.entrySet()) {
				if (entry.getKey().toString().equals("FRAGMENT")) {
					return (String) entry.getValue();
				}
			}
			throw new AssertionError("no fragment shader in " + result.keySet());
		} finally {
			thread.setContextClassLoader(previousContext);
			deviceField.set(null, null);
			launcherField.set(null, previousLauncher);
		}
	}

	/** Iris's transform cache only asks the device for its depth range; nothing else is used. */
	private static com.mojang.renderpearl.api.device.GpuDevice fakeDevice(final boolean zeroToOne) {
		var info = new com.mojang.renderpearl.api.device.DeviceInfo(
			"test", "test", "test", zeroToOne, zeroToOne ? "Vulkan" : "OpenGL", 1.0F, null, null, java.util.Set.of(), null, null
		);
		return (com.mojang.renderpearl.api.device.GpuDevice) java.lang.reflect.Proxy.newProxyInstance(
			IrisTransformTest.class.getClassLoader(),
			new Class<?>[] {com.mojang.renderpearl.api.device.GpuDevice.class},
			(proxy, method, args) -> {
				if (method.getName().equals("getDeviceInfo")) {
					return info;
				}
				throw new UnsupportedOperationException("test device: " + method.getName());
			}
		);
	}

	/** Iris plus the libraries it bundles, loaded child-first so the other Iris on the test classpath is not used. */
	private static URLClassLoader isolatedIris(final Path jar) throws IOException {
		List<URL> urls = new ArrayList<>();
		urls.add(jar.toUri().toURL());
		Path nested = Path.of(System.getProperty("spritestream.testTmp", System.getProperty("java.io.tmpdir")), jar.getFileName().toString());
		Files.createDirectories(nested);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (entry.getName().startsWith("META-INF/jars/") && entry.getName().endsWith(".jar")) {
					Path out = nested.resolve(entry.getName().substring("META-INF/jars/".length()));
					try (InputStream in = zip.getInputStream(entry)) {
						Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
					}
					urls.add(out.toUri().toURL());
				}
			}
		}
		return new ChildFirst(urls.toArray(URL[]::new), IrisTransformTest.class.getClassLoader());
	}

	private static final class ChildFirst extends URLClassLoader {
		ChildFirst(final URL[] urls, final ClassLoader parent) {
			super(urls, parent);
		}

		/** Service files come from the isolated jars only, or Iris would find the other version's implementations. */
		@Override
		public java.util.Enumeration<URL> getResources(final String name) throws IOException {
			return name.startsWith("META-INF/services/net.irisshaders.") ? this.findResources(name) : super.getResources(name);
		}

		@Override
		protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
			boolean isolated = name.startsWith("net.irisshaders.") || name.startsWith("io.github.douira.")
				|| name.startsWith("org.antlr.") || name.startsWith("org.anarres.");
			if (!isolated) {
				return super.loadClass(name, resolve);
			}
			synchronized (this.getClassLoadingLock(name)) {
				Class<?> found = this.findLoadedClass(name);
				if (found == null) {
					try {
						found = this.findClass(name);
					} catch (ClassNotFoundException e) {
						found = super.loadClass(name, false);
					}
				}
				if (resolve) {
					this.resolveClass(found);
				}
				return found;
			}
		}
	}

	private static void compile(final String source, final int env, final int envVersion) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		Shaderc.shaderc_compile_options_set_target_env(options, env, envVersion);
		Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
		Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
		if (env == Shaderc.shaderc_target_env_vulkan) {
			// Iris 1.12's own output still has loose (non-sampler) uniforms at this stage; Iris deals with those later
			// in its pipeline. Relaxed rules accept them, so the check is about our additions next to Iris's code.
			Shaderc.shaderc_compile_options_set_vulkan_rules_relaxed(options, true);
		}
		ByteBuffer src = MemoryUtil.memUTF8(source, false);
		ByteBuffer name = MemoryUtil.memUTF8("gbuffers_terrain.fsh");
		ByteBuffer entry = MemoryUtil.memUTF8("main");
		long result = Shaderc.shaderc_compile_into_spv(compiler, src, Shaderc.shaderc_fragment_shader, name, entry, options);
		try {
			assertEquals(
				Shaderc.shaderc_compilation_status_success, Shaderc.shaderc_result_get_compilation_status(result),
				Shaderc.shaderc_result_get_error_message(result) + "\n" + numbered(source)
			);
		} finally {
			Shaderc.shaderc_result_release(result);
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
			MemoryUtil.memFree(src);
			MemoryUtil.memFree(name);
			MemoryUtil.memFree(entry);
		}
	}

	private static String numbered(final String source) {
		StringBuilder out = new StringBuilder();
		String[] lines = source.split("\n");
		for (int i = 0; i < lines.length; i++) {
			out.append(i + 1).append(": ").append(lines[i]).append('\n');
		}
		return out.toString();
	}
}
