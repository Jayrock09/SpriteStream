package dev.spritestream.mixin.sodium;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.spritestream.StreamManager;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Declares the pool's textures and sprite table on Sodium's terrain pipelines. */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ShaderChunkRendererMixin {
	@Redirect(
		method = {"createShader", "createOITShader"},
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;withBindGroupLayout(Lcom/mojang/renderpearl/api/pipeline/BindGroupLayout;)Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;",
			ordinal = 0
		)
	)
	private RenderPipeline.Builder spritestream$addBindGroup(final RenderPipeline.Builder builder, final BindGroupLayout layout) {
		return builder.withBindGroupLayout(layout).withBindGroupLayout(StreamManager.BIND_GROUP);
	}
}
