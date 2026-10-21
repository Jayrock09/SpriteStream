package dev.spritestream;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

/**
 * GLSL patching for both terrain paths.
 *
 * <p>Sodium without a shader pack: the colour Sodium samples from the block atlas is replaced by the pool
 * texel when one is resident.
 *
 * <p>Iris shader packs: every {@code texture}, {@code textureLod} and {@code textureGrad} call on
 * {@code gtexture}, {@code normals} or {@code specular} is routed through a function that first samples
 * the base atlas exactly as the pack asked, then swaps in the pool's colour, normal or specular texel for
 * that sprite when a finer one is resident. Atlas UVs are kept, so parallax (POM) code that walks UVs
 * inside a tile keeps working; each step is looked up again.
 */
public final class ShaderPatch {
	public static final Identifier SODIUM_TERRAIN = Identifier.fromNamespaceAndPath("sodium", "blocks/block_layer_opaque");

	private static final String SODIUM_ANCHOR = "uniform sampler2D u_BlockTex;";
	private static final Pattern SODIUM_COLOR_SAMPLE = Pattern.compile("vec4 color = (u_UseRGSS[^;]*);");
	private static final Pattern VERSION = Pattern.compile("#version\\s+(\\d+)");
	private static final Pattern CALL = Pattern.compile("\\b(texture|textureLod|textureGrad)\\s*\\(\\s*(gtexture|normals|specular)\\s*,");

	/** Pack sampler name, pool sampler, plane index, function suffix. */
	private record Plane(String packSampler, String poolSampler, int index, String suffix) {
	}

	private static final Plane[] PLANES = {
		new Plane("gtexture", "ss_Color", PoolPage.PLANE_COLOR, "c"),
		new Plane("normals", "ss_Normal", PoolPage.PLANE_NORMAL, "n"),
		new Plane("specular", "ss_Specular", PoolPage.PLANE_SPECULAR, "s"),
	};

	private static final String COMMON = """
		int ss_spriteId(vec2 uv) {
		    ivec2 size = textureSize(ss_IdMap, 0);
		    vec4 t = texelFetch(ss_IdMap, clamp(ivec2(uv * vec2(size)), ivec2(0), size - ivec2(1)), 0);
		    return int(t.r * 255.0 + 0.5) | (int(t.g * 255.0 + 0.5) << 8);
		}

		vec4 ss_entry(int id, int k) {
		    return texelFetch(ss_Table, ivec2((id & 255) * 4 + k, id >> 8), 0);
		}

		// Maps an atlas UV into the pool. Returns false when the sprite is not resident, lacks this plane, or
		// the base atlas already holds as much detail as the footprint needs. explicitLod < 0 means "from the
		// gradients"; otherwise it is a level in pool texels (0 = sharpest).
		bool ss_resolve(int plane, vec2 uv, vec2 dx, vec2 dy, float explicitLod,
		                out vec2 puv, out vec2 pdx, out vec2 pdy, out float lod, out float fade) {
		    puv = vec2(0.0); pdx = vec2(0.0); pdy = vec2(0.0); lod = 0.0; fade = 0.0;
		    int id = ss_spriteId(uv);
		    if (id == 0) return false;
		    vec4 slot = ss_entry(id, 1);
		    if (slot.x < 0.5) return false;
		    vec4 info = ss_entry(id, 3);
		    if ((plane == 1 && info.y < 0.5) || (plane == 2 && info.z < 0.5)) return false;
		    vec4 rect = ss_entry(id, 0);
		    vec4 ext = ss_entry(id, 2);
		    vec2 scale = ext.xy / (rect.zw - rect.xy);
		    pdx = dx * scale;
		    pdy = dy * scale;
		    lod = explicitLod >= 0.0 ? explicitLod : log2(max(max(length(pdx / ext.zw), length(pdy / ext.zw)), 1e-6));
		    if (lod >= info.x) return false;
		    // Keep the filter inside this block so neighbouring blocks never bleed in.
		    vec2 inset = min(0.5 * exp2(max(ceil(lod), 0.0)) * ext.zw, ext.xy * 0.5);
		    puv = slot.yz + clamp((uv - rect.xy) * scale, inset, ext.xy - inset);
		    fade = slot.w;
		    return true;
		}
		""";

	private static final String PLANE_TEMPLATE = """
		vec4 ss_grad_$S(vec2 uv, vec2 dx, vec2 dy) {
		    vec4 base = textureGrad($B, uv, dx, dy);
		    vec2 puv; vec2 pdx; vec2 pdy; float lod; float fade;
		    if (!ss_resolve($K, uv, dx, dy, -1.0, puv, pdx, pdy, lod, fade)) return base;
		    return mix(base, textureGrad($P, puv, pdx, pdy), fade);
		}
		vec4 ss_tex_$S(vec2 uv) {
		    return ss_grad_$S(uv, dFdx(uv), dFdy(uv));
		}
		vec4 ss_tex_$S(vec2 uv, float bias) {
		    float s = exp2(bias);
		    return ss_grad_$S(uv, dFdx(uv) * s, dFdy(uv) * s);
		}
		vec4 ss_lod_$S(vec2 uv, float level) {
		    vec4 base = textureLod($B, uv, level);
		    vec2 puv; vec2 pdx; vec2 pdy; float lod; float fade;
		    if (!ss_resolve($K, uv, vec2(0.0), vec2(0.0), max(level, 0.0), puv, pdx, pdy, lod, fade)) return base;
		    return mix(base, textureLod($P, puv, lod), fade);
		}
		""";

	private static final String PLANE_PROTOTYPES = """
		vec4 ss_grad_$S(vec2 uv, vec2 dx, vec2 dy);
		vec4 ss_tex_$S(vec2 uv);
		vec4 ss_tex_$S(vec2 uv, float bias);
		vec4 ss_lod_$S(vec2 uv, float level);
		""";

	private ShaderPatch() {
	}

	// ---------------------------------------------------------------- Sodium (no shader pack)

	public static String patchSodiumFragment(final String source) {
		if (source.contains("ss_resolve(")) {
			return source;
		}
		int anchor = source.indexOf(SODIUM_ANCHOR);
		Matcher matcher = SODIUM_COLOR_SAMPLE.matcher(source);
		if (anchor < 0 || !matcher.find()) {
			SpriteStreamMod.LOGGER.error("[SpriteStream] Sodium terrain shader layout not recognised; high-res streaming will not show");
			return source;
		}
		String sampled = source.substring(0, matcher.start())
			+ "vec4 color = ss_apply(" + matcher.group(1) + ", v_TexCoord);"
			+ source.substring(matcher.end());
		String library = "\nuniform sampler2D ss_IdMap;\nuniform sampler2D ss_Table;\nuniform sampler2D ss_Color;\n" + COMMON + """
			vec4 ss_apply(vec4 baseColor, vec2 uv) {
			    vec2 dx = dFdx(uv);
			    vec2 dy = dFdy(uv);
			    vec2 puv; vec2 pdx; vec2 pdy; float lod; float fade;
			    if (!ss_resolve(0, uv, dx, dy, -1.0, puv, pdx, pdy, lod, fade)) return baseColor;
			    return mix(baseColor, textureGrad(ss_Color, puv, pdx, pdy), fade);
			}
			""";
		int insertAt = sampled.indexOf(SODIUM_ANCHOR) + SODIUM_ANCHOR.length();
		SpriteStreamMod.LOGGER.info("[SpriteStream] Patched Sodium terrain shader");
		return sampled.substring(0, insertAt) + library + sampled.substring(insertAt);
	}

	// ---------------------------------------------------------------- Iris shader packs

	/** Patches one fragment shader produced by Iris for a Sodium terrain program. */
	public static String patchIrisFragment(final String name, final String source) {
		if (source == null || source.contains("ss_resolve(")) {
			return source;
		}
		Matcher version = VERSION.matcher(source);
		if (version.find() && Integer.parseInt(version.group(1)) < 130) {
			SpriteStreamMod.LOGGER.warn("[SpriteStream] {} uses GLSL {}; leaving it unpatched", name, version.group(1));
			return source;
		}

		List<Plane> planes = new ArrayList<>();
		int lastDeclarationEnd = -1;
		for (Plane plane : PLANES) {
			Matcher declaration = Pattern.compile("uniform\\s+(?:\\w+\\s+)*sampler2D\\s+" + plane.packSampler + "\\s*;").matcher(source);
			if (declaration.find()) {
				planes.add(plane);
				lastDeclarationEnd = Math.max(lastDeclarationEnd, declaration.end());
			}
		}
		if (planes.isEmpty()) {
			return source;
		}

		int[] rewrites = new int[1];
		String body = rewriteCalls(source, planes, rewrites);
		if (rewrites[0] == 0) {
			return source;
		}
		if (lastDeclarationEnd < 0) {
			return source;
		}

		StringBuilder prototypes = new StringBuilder("\nuniform sampler2D ss_IdMap;\nuniform sampler2D ss_Table;\n");
		StringBuilder bodies = new StringBuilder("\n").append(COMMON);
		for (Plane plane : planes) {
			prototypes.append("uniform sampler2D ").append(plane.poolSampler).append(";\n");
			prototypes.append(fill(PLANE_PROTOTYPES, plane));
			bodies.append(fill(PLANE_TEMPLATE, plane));
		}

		// Prototypes go after the preprocessor preamble so any later function can call them; the bodies go
		// after the pack's own sampler declarations, which they reference.
		// Right after the declaration's ';' is always global scope, however Iris laid out the lines.
		int bodyAt = findDeclarationEnd(body, planes);
		String withBodies = body.substring(0, bodyAt) + bodies + body.substring(bodyAt);
		int preambleEnd = preambleEnd(withBodies);
		SpriteStreamMod.LOGGER.info("[SpriteStream] Patched {} ({} texture calls, planes {})", name, rewrites[0], planes.stream().map(Plane::packSampler).toList());
		return withBodies.substring(0, preambleEnd) + prototypes + withBodies.substring(preambleEnd);
	}

	private static String fill(final String template, final Plane plane) {
		return template.replace("$S", plane.suffix)
			.replace("$B", plane.packSampler)
			.replace("$P", plane.poolSampler)
			.replace("$K", Integer.toString(plane.index));
	}

	private static int findDeclarationEnd(final String source, final List<Plane> planes) {
		int end = 0;
		for (Plane plane : planes) {
			Matcher declaration = Pattern.compile("uniform\\s+(?:\\w+\\s+)*sampler2D\\s+" + plane.packSampler + "\\s*;").matcher(source);
			if (declaration.find()) {
				end = Math.max(end, declaration.end());
			}
		}
		return end;
	}

	/** End of the leading block of preprocessor lines (#version, #extension, ...). */
	private static int preambleEnd(final String source) {
		int pos = 0;
		int end = 0;
		while (pos < source.length()) {
			int lineEnd = source.indexOf('\n', pos);
			if (lineEnd < 0) {
				lineEnd = source.length();
			}
			String line = source.substring(pos, lineEnd).trim();
			if (line.startsWith("#")) {
				end = Math.min(source.length(), lineEnd + 1);
			} else if (!line.isEmpty()) {
				break;
			}
			pos = lineEnd + 1;
		}
		return end;
	}

	/** Rewrites atlas sampling calls, recursing into their arguments; counts rewrites in {@code count[0]}. */
	static String rewriteCalls(final String source, final List<Plane> planes, final int[] count) {
		StringBuilder out = new StringBuilder(source.length() + 256);
		Matcher matcher = CALL.matcher(source);
		int copied = 0;
		while (matcher.find(copied)) {
			Plane plane = null;
			for (Plane candidate : planes) {
				if (candidate.packSampler.equals(matcher.group(2))) {
					plane = candidate;
				}
			}
			int argsStart = matcher.end();
			int close = matchingParen(source, matcher.start() + matcher.group(0).indexOf('('));
			if (plane == null || close < 0) {
				out.append(source, copied, matcher.end());
				copied = matcher.end();
				continue;
			}
			String args = rewriteCalls(source.substring(argsStart, close), planes, count);
			String function = switch (matcher.group(1)) {
				case "textureLod" -> "ss_lod_";
				case "textureGrad" -> "ss_grad_";
				default -> "ss_tex_";
			};
			out.append(source, copied, matcher.start()).append(function).append(plane.suffix).append('(').append(args).append(')');
			count[0]++;
			copied = close + 1;
		}
		out.append(source, copied, source.length());
		return out.toString();
	}

	private static int matchingParen(final String source, final int open) {
		int depth = 0;
		for (int i = open; i < source.length(); i++) {
			char c = source.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}
}
