package dev.spritestream;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Checks, before the Iris hooks are applied, that the installed Iris still has every method and field they hook
 * or call. A mismatch would otherwise crash the game while mixins apply; with this check the hooks are left
 * out and SpriteStream turns itself off instead.
 */
public final class IrisHookCheck {
	private static final String LOADER = "net/irisshaders/iris/pbr/loader/AtlasPBRLoader";
	private static final String SAMPLER_HOLDER = "net/irisshaders/iris/gl/sampler/SamplerHolder";
	private static final String GL_SAMPLER = "net/irisshaders/iris/gl/sampler/GlSampler";

	/** A member the hooks need: a method (descriptor starts with '(') or a field. */
	private record Need(String owner, String name, String descriptor, @Nullable String calls) {
	}

	private static final List<Need> NEEDS = List.of(
		new Need(
			"net/irisshaders/iris/samplers/IrisSamplers",
			"addLevelSamplers",
			"(L" + SAMPLER_HOLDER + ";Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;Lnet/minecraft/client/renderer/texture/AbstractTexture;ZZZ)V",
			null
		),
		new Need(SAMPLER_HOLDER, "addTextureSampler", "(L" + SAMPLER_HOLDER + "$TextureBinding;Z[Ljava/lang/String;)Z", null),
		new Need(
			SAMPLER_HOLDER + "$TextureBinding",
			"<init>",
			"(Ljava/lang/Object;Ljava/util/function/Supplier;Ljava/util/function/Supplier;Ljava/util/function/Supplier;)V",
			null
		),
		new Need(GL_SAMPLER, "NEAREST", "L" + GL_SAMPLER + ";", null),
		new Need(GL_SAMPLER, "MIPPED_LINEAR", "L" + GL_SAMPLER + ";", null),
		new Need(GL_SAMPLER, "MIPPED_NEAREST_NEAREST", "L" + GL_SAMPLER + ";", null),
		new Need(
			"net/irisshaders/iris/pipeline/transform/TransformPatcher",
			"patchSodium",
			"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
				+ "Lnet/irisshaders/iris/gl/blending/AlphaTest;Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;Ljava/util/Set;Z)Ljava/util/Map;",
			null
		),
		new Need(
			LOADER,
			"load",
			"(Lnet/minecraft/client/renderer/texture/TextureAtlas;Lnet/minecraft/server/packs/resources/ResourceManager;"
				+ "Lnet/irisshaders/iris/pbr/loader/PBRTextureLoader$PBRTextureConsumer;)V",
			null
		),
		new Need(
			LOADER,
			"getPBRImageLocation",
			"(Lnet/minecraft/resources/Identifier;Lnet/irisshaders/iris/pbr/texture/PBRType;)Lnet/minecraft/resources/Identifier;",
			null
		),
		new Need(
			LOADER,
			"createPBRSprite",
			"(Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraft/server/packs/resources/ResourceManager;"
				+ "Lnet/minecraft/client/renderer/texture/TextureAtlas;IIILnet/irisshaders/iris/pbr/texture/PBRType;)L" + LOADER + "$PBRTextureAtlasSprite;",
			"com/mojang/blaze3d/platform/NativeImage.read(Ljava/io/InputStream;)Lcom/mojang/blaze3d/platform/NativeImage;"
		),
		new Need("net/irisshaders/iris/mixin/texture/TextureAtlasAccessor", "getTexturesByName", "()Ljava/util/Map;", null),
		new Need("net/irisshaders/iris/pbr/texture/PBRType", "values", "()[Lnet/irisshaders/iris/pbr/texture/PBRType;", null)
	);

	private IrisHookCheck() {
	}

	/**
	 * Returns what the hooks need but the installed Iris lacks; empty when everything is there.
	 *
	 * @param classBytes looks up a class file by internal name, or returns null when the class does not exist
	 */
	public static List<String> missing(final Function<String, byte @Nullable []> classBytes) {
		List<String> missing = new ArrayList<>();
		java.util.Map<String, @Nullable ClassNode> classes = new java.util.HashMap<>();
		for (Need need : NEEDS) {
			ClassNode node = classes.computeIfAbsent(need.owner(), owner -> read(classBytes.apply(owner)));
			String label = need.owner().substring(need.owner().lastIndexOf('/') + 1) + "." + need.name();
			if (node == null) {
				missing.add(label + " (class not found)");
			} else if (need.descriptor().startsWith("(")) {
				MethodNode method = findMethod(node, need.name(), need.descriptor());
				if (method == null) {
					missing.add(label + need.descriptor());
				} else if (need.calls() != null && !calls(method, need.calls())) {
					missing.add(label + " no longer calls " + need.calls());
				}
			} else if (!hasField(node, need.name(), need.descriptor())) {
				missing.add(label);
			}
		}
		return missing;
	}

	/** Uses class files visible to the given loader, which at mixin time can see every mod's classes. */
	public static List<String> missing(final ClassLoader loader) {
		return missing(name -> {
			try (var in = loader.getResourceAsStream(name + ".class")) {
				return in == null ? null : in.readAllBytes();
			} catch (java.io.IOException e) {
				return null;
			}
		});
	}

	private static @Nullable ClassNode read(final byte @Nullable [] bytes) {
		if (bytes == null) {
			return null;
		}
		try {
			ClassNode node = new ClassNode(Opcodes.ASM9);
			new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static @Nullable MethodNode findMethod(final ClassNode node, final String name, final String descriptor) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(descriptor)) {
				return method;
			}
		}
		return null;
	}

	private static boolean hasField(final ClassNode node, final String name, final String descriptor) {
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(descriptor)) {
				return true;
			}
		}
		return false;
	}

	/** {@code target} is {@code owner.name(descriptor)}. */
	private static boolean calls(final MethodNode method, final String target) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && target.equals(call.owner + "." + call.name + call.desc)) {
				return true;
			}
		}
		return false;
	}
}
