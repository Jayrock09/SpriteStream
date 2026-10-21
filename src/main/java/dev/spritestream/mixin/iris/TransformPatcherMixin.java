package dev.spritestream.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.spritestream.ShaderPatch;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Routes the shader pack's atlas sampling through the pool, for the terrain programs Iris builds on top of
 * Sodium. The shadow pass is left alone: it only needs alpha for cut-outs, which the base atlas has.
 */
@Mixin(value = TransformPatcher.class, remap = false)
public abstract class TransformPatcherMixin {
	@ModifyReturnValue(method = "patchSodium", at = @At("RETURN"))
	private static Map<PatchShaderType, String> spritestream$patchTerrain(
		final Map<PatchShaderType, String> result,
		final String name, final String vertex, final String geometry, final String tessControl, final String tessEval, final String fragment,
		final AlphaTest alpha, final Object2ObjectMap<?, String> textureMap, final Set<String> textureOverrides, final boolean shadow
	) {
		if (result == null || shadow || result.get(PatchShaderType.FRAGMENT) == null) {
			return result;
		}
		// The result is cached by Iris, so patch a copy rather than the cached map.
		EnumMap<PatchShaderType, String> patched = new EnumMap<>(PatchShaderType.class);
		patched.putAll(result);
		patched.put(PatchShaderType.FRAGMENT, ShaderPatch.patchIrisFragment(name, result.get(PatchShaderType.FRAGMENT)));
		return patched;
	}
}
