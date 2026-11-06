package dev.spritestream.mixin;

import dev.spritestream.IrisCompat;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Adds the Iris hooks only when Iris 1.12+ is installed, this jar was built with them, and the installed Iris still
 * has everything they hook ({@link dev.spritestream.IrisHookCheck}). They are not listed in the mixin config, so any
 * other Iris (or no Iris) never sees them, and a changed Iris turns SpriteStream off instead of crashing the game.
 */
public final class SpriteStreamMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(final String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
		return true;
	}

	@Override
	public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		if (IrisCompat.hooksUsable()) {
			return List.of("iris.IrisSamplersMixin", "iris.TransformPatcherMixin", "iris.AtlasPBRLoaderMixin");
		}
		return null;
	}

	@Override
	public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
	}
}
