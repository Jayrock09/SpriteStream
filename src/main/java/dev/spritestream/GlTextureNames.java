package dev.spritestream;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTextureView;

/** OpenGL texture names of our textures, for Iris 1.11, which binds samplers by GL name. */
public final class GlTextureNames {
	private static boolean warned;

	private GlTextureNames() {
	}

	/**
	 * The GL name of {@code view}, or 0 when it is not an OpenGL texture. With 0 bound, our shader code reads
	 * sprite id 0 from the id map and falls back to the pack's own atlas, so a wrong backend degrades to base
	 * textures instead of garbage.
	 */
	public static int of(final GpuTextureView view) {
		if (view instanceof GlTextureView gl) {
			return gl.glId();
		}
		if (!warned) {
			warned = true;
			SpriteStreamMod.LOGGER.warn("[SpriteStream] Iris 1.11 asked for an OpenGL texture but the game is not on OpenGL ({}); "
				+ "shader packs will show base-resolution textures", RenderBackend.name());
		}
		return 0;
	}
}
