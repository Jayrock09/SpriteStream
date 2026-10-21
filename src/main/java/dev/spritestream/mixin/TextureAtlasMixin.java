package dev.spritestream.mixin;

import dev.spritestream.StreamManager;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rebuilds the streaming tables whenever the block atlas is re-stitched. */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
	@Shadow
	@Final
	private Identifier location;

	@SuppressWarnings("deprecation")
	@Inject(method = "upload", at = @At("TAIL"))
	private void spritestream$onUpload(final SpriteLoader.Preparations preparations, final CallbackInfo ci) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(this.location)) {
			StreamManager.get().onBlockAtlasUploaded(preparations);
		}
	}
}
