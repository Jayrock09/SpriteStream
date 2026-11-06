package dev.spritestream;

import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;

/** The render backend in use. Kept apart from {@link IrisCompat}, which loads during mixin setup. */
public final class RenderBackend {
	private RenderBackend() {
	}

	/** "OpenGL" or "Vulkan"; null before the render device exists. */
	public static @Nullable String name() {
		try {
			return RenderSystem.getDevice().getDeviceInfo().backendName();
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}
}
