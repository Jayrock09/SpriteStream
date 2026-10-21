package dev.spritestream.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.spritestream.ShaderPatch;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hands the patched Sodium terrain fragment shader to the pipeline compiler. */
@Mixin(ShaderManager.Configs.class)
public abstract class ShaderConfigsMixin {
	@ModifyReturnValue(method = "getShader", at = @At("RETURN"))
	private String spritestream$patch(final String source, final Identifier id, final ShaderType type) {
		if (source != null && type == ShaderType.FRAGMENT && ShaderPatch.SODIUM_TERRAIN.equals(id)) {
			return ShaderPatch.patchSodiumFragment(source);
		}
		return source;
	}
}
