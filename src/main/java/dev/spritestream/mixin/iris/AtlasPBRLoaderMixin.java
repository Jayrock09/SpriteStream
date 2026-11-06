package dev.spritestream.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.NativeImage;
import dev.spritestream.BaseAtlasLoader;
import dev.spritestream.IrisPbrPrefetch;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import net.irisshaders.iris.mixin.texture.TextureAtlasAccessor;
import net.irisshaders.iris.pbr.loader.AtlasPBRLoader;
import net.irisshaders.iris.pbr.loader.PBRTextureLoader;
import net.irisshaders.iris.pbr.texture.PBRType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds Iris's PBR atlas builds from {@link IrisPbrPrefetch} instead of decoding full-size maps one by one on the
 * render thread. Maps are shrunk while the resource pack loads (block atlas and the capped atlases: items, chests,
 * ...) and kept for the reload, so turning shaders on or off reuses them; anything not cached by then is shrunk
 * in parallel right before Iris's build.
 */
@Mixin(value = AtlasPBRLoader.class, remap = false)
public abstract class AtlasPBRLoaderMixin {
	@Shadow
	protected abstract Identifier getPBRImageLocation(Identifier spriteName, PBRType pbrType);

	@SuppressWarnings("deprecation")
	@Inject(method = "load", at = @At("HEAD"))
	private void spritestream$prefetch(
		final TextureAtlas atlas, final ResourceManager resourceManager, final PBRTextureLoader.PBRTextureConsumer consumer, final CallbackInfo ci
	) {
		if (!BaseAtlasLoader.isActive()) {
			return;
		}
		List<IrisPbrPrefetch.Request> requests = new ArrayList<>();
		for (TextureAtlasSprite sprite : ((TextureAtlasAccessor) atlas).getTexturesByName().values()) {
			if (sprite.contents().isAnimated()) {
				continue;
			}
			for (PBRType type : PBRType.values()) {
				Identifier location = this.getPBRImageLocation(sprite.contents().name(), type);
				resourceManager.getResource(location).ifPresent(resource -> requests.add(
					new IrisPbrPrefetch.Request(location, resource, sprite.contents().width(), sprite.contents().height())
				));
			}
		}
		IrisPbrPrefetch.prefetchMissing(requests);
	}

	@WrapOperation(
		method = "createPBRSprite",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/NativeImage;read(Ljava/io/InputStream;)Lcom/mojang/blaze3d/platform/NativeImage;")
	)
	private NativeImage spritestream$useShrunk(
		final InputStream stream, final Operation<NativeImage> original,
		@Local(argsOnly = true) final TextureAtlasSprite sprite, @Local(ordinal = 1) final Identifier pbrImageLocation
	) {
		NativeImage ready = IrisPbrPrefetch.copyOf(pbrImageLocation, sprite.contents().width(), sprite.contents().height());
		return ready != null ? ready : original.call(stream);
	}
}
