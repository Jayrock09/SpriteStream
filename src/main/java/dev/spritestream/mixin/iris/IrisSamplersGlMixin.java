package dev.spritestream.mixin.iris;

import dev.spritestream.GlTextureNames;
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
 * Iris 1.11 version of {@link IrisSamplersMixin}. Iris 1.11 has no texture-view samplers; it binds OpenGL texture
 * names with its own sampler objects, and only runs shader packs on OpenGL. The names are looked up every time
 * Iris binds, because the pool is rebuilt when a shader pack is turned on or off.
 */
@Mixin(value = IrisSamplers.class, remap = false)
public abstract class IrisSamplersGlMixin {
	@Inject(method = "addLevelSamplers", at = @At("TAIL"))
	private static void spritestream$addPool(
		final SamplerHolder samplers, final WorldRenderingPipeline pipeline, final AbstractTexture whitePixel,
		final boolean hasTexture, final boolean hasLightmap, final boolean hasOverlay, final CallbackInfo ci
	) {
		StreamManager manager = StreamManager.get();
		samplers.addDynamicSampler(() -> GlTextureNames.of(manager.idMapView()), GlSampler.NEAREST, "ss_IdMap");
		samplers.addDynamicSampler(() -> GlTextureNames.of(manager.tableView()), GlSampler.NEAREST, "ss_Table");
		samplers.addDynamicSampler(() -> GlTextureNames.of(manager.planeView(0)), GlSampler.MIPPED_LINEAR, "ss_Color");
		// LabPBR data must not be blended between texels (see StreamManager#pbrSampler).
		samplers.addDynamicSampler(() -> GlTextureNames.of(manager.planeView(1)), GlSampler.MIPPED_NEAREST_NEAREST, "ss_Normal");
		samplers.addDynamicSampler(() -> GlTextureNames.of(manager.planeView(2)), GlSampler.MIPPED_NEAREST_NEAREST, "ss_Specular");
	}
}
