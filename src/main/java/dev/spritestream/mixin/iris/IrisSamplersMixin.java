package dev.spritestream.mixin.iris;

import dev.spritestream.PoolPage;
import dev.spritestream.StreamManager;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Registers the pool by name next to {@code gtexture}, {@code normals} and {@code specular}. Iris binds a
 * registered sampler only in programs that declare it, which only our patched terrain shaders do, and it
 * does so for both its OpenGL and Vulkan backends.
 */
@Mixin(value = IrisSamplers.class, remap = false)
public abstract class IrisSamplersMixin {
	@Inject(method = "addLevelSamplers", at = @At("TAIL"))
	private static void spritestream$addPool(
		final SamplerHolder samplers, final WorldRenderingPipeline pipeline, final AbstractTexture whitePixel,
		final boolean hasTexture, final boolean hasLightmap, final boolean hasOverlay, final CallbackInfo ci
	) {
		StreamManager manager = StreamManager.get();
		samplers.addTextureSampler(
			new SamplerHolder.TextureBinding("spritestream/idmap", manager::idMapView, () -> GlSampler.NEAREST, manager::nearestSampler), false, "ss_IdMap"
		);
		samplers.addTextureSampler(
			new SamplerHolder.TextureBinding("spritestream/table", manager::tableView, () -> GlSampler.NEAREST, manager::nearestSampler), false, "ss_Table"
		);
		String[] names = {"ss_Color", "ss_Normal", "ss_Specular"};
		for (int plane = 0; plane < names.length; plane++) {
			int p = plane;
			samplers.addTextureSampler(
				new SamplerHolder.TextureBinding("spritestream/plane" + p, () -> manager.planeView(p), () -> GlSampler.MIPPED_LINEAR, manager::poolSampler),
				false,
				names[p]
			);
		}
	}
}
