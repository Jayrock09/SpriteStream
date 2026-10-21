package dev.spritestream.mixin;

import dev.spritestream.BaseAtlasLoader;
import dev.spritestream.SpriteStreamMod;
import java.util.Set;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Swaps in the shrinking loader for the block atlas only. */
@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderMixin {
	private static final Identifier GUI_ATLAS = Identifier.withDefaultNamespace("textures/atlas/gui.png");

	@Shadow
	@Final
	private Identifier location;

	@SuppressWarnings("deprecation")
	@Redirect(
		method = "loadAndStitch",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/atlas/SpriteResourceLoader;create(Ljava/util/Set;)Lnet/minecraft/client/renderer/texture/atlas/SpriteResourceLoader;"
		)
	)
	private SpriteResourceLoader spritestream$wrapLoader(final Set<MetadataSectionType<?>> additionalMetadata) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(this.location)) {
			BaseAtlasLoader.beginLoad();
			return BaseAtlasLoader.createForBlocks(additionalMetadata);
		}
		// GUI sprites carry nine-slice borders in pixels, so shrinking them would break their layout.
		int cap = SpriteStreamMod.config.otherAtlasResolution;
		if (cap > 0 && !SpriteStreamMod.DISABLED && !GUI_ATLAS.equals(this.location)) {
			return BaseAtlasLoader.createCapped(additionalMetadata, cap);
		}
		return SpriteResourceLoader.create(additionalMetadata);
	}
}
