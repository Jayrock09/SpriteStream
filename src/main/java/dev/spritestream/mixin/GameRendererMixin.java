package dev.spritestream.mixin;

import dev.spritestream.StreamManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives the streamer once per frame, after the camera has moved and before anything is drawn. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "update", at = @At("TAIL"))
	private void spritestream$frame(final DeltaTracker deltaTracker, final CallbackInfo ci) {
		StreamManager.get().frame();
	}
}
