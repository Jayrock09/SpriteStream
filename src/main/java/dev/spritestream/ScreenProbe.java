package dev.spritestream;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Measures how many screen pixels each block texture covers, without touching the GPU.
 *
 * <p>A grid of rays is cast through the screen, jittered every frame so that over a few frames it samples
 * the screen densely. For each hit we know the face, its sprites and its depth along the view axis, which
 * gives the exact pixels-per-block at that spot: {@code screenHeight / (2 * depth * tan(fovY / 2))}. That is
 * the number of texels the texture needs for one texel per pixel. Zooming lowers the FOV, which raises the
 * pixels-per-block, so zoomed-in distant blocks get upgraded like near ones.
 */
public final class ScreenProbe {
	/** Callback receiving one measurement: the sprite and the texels-per-block it needs at that pixel. */
	public interface Sink {
		void accept(TextureAtlasSprite sprite, float texelsPerBlock);
	}

	private static final TextureAtlasSprite[] NO_SPRITES = new TextureAtlasSprite[0];
	private static final int JITTER = 4;

	/** Sprites per (state, face); index 6 holds the unculled quads used when no face matches. */
	private final Map<BlockState, TextureAtlasSprite[][]> spriteCache = new IdentityHashMap<>();
	private final RandomSource random = RandomSource.create();
	private int frame;

	public void clearCache() {
		this.spriteCache.clear();
	}

	/** Casts this frame's rays; returns the number of rays cast. */
	public int run(final Minecraft minecraft, final SpriteStreamConfig config, final Sink sink) {
		ClientLevel level = minecraft.level;
		Camera camera = minecraft.gameRenderer.mainCamera();
		if (level == null || !camera.isInitialized()) {
			return 0;
		}

		int screenW = minecraft.getWindow().getWidth();
		int screenH = minecraft.getWindow().getHeight();
		if (screenW <= 0 || screenH <= 0) {
			return 0;
		}

		float tanHalfY = (float) Math.tan(Math.toRadians(camera.getFov()) * 0.5);
		float aspect = (float) screenW / screenH;
		// Beyond this depth one block covers fewer pixels than the base texture has texels.
		float maxDepth = Math.min(512.0F, screenH * config.qualityBias / (2.0F * tanHalfY * config.baseResolution));

		Vec3 origin = camera.position();
		Vector3fc forward = camera.forwardVector();
		Vector3fc up = camera.upVector();
		Vector3f right = new Vector3f(camera.leftVector()).negate();
		Vector3f dir = new Vector3f();

		int cols = config.probeColumns;
		int rows = config.probeRows;
		int step = this.frame++ % (JITTER * JITTER);
		float jx = ((step % JITTER) + 0.5F) / JITTER;
		float jy = ((step / JITTER) + 0.5F) / JITTER;
		BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
		int rays = 0;

		for (int row = 0; row < rows; row++) {
			float ndcY = 1.0F - 2.0F * (row + jy) / rows;
			for (int col = 0; col < cols; col++) {
				float ndcX = 2.0F * (col + jx) / cols - 1.0F;
				dir.set(forward)
					.fma(ndcX * tanHalfY * aspect, right)
					.fma(ndcY * tanHalfY, up);
				// dir has unit length along the view axis, so depth == distance travelled along it.
				Vec3 end = origin.add(dir.x * maxDepth, dir.y * maxDepth, dir.z * maxDepth);
				BlockHitResult hit = level.clip(new ClipContext(origin, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
				rays++;
				if (hit.getType() != HitResult.Type.BLOCK) {
					continue;
				}

				Vec3 at = hit.getLocation();
				float depth = (float) ((at.x - origin.x) * forward.x() + (at.y - origin.y) * forward.y() + (at.z - origin.z) * forward.z());
				if (depth <= 0.05F) {
					depth = 0.05F;
				}
				float texelsPerBlock = screenH * config.qualityBias / (2.0F * depth * tanHalfY);

				BlockPos pos = hit.getBlockPos();
				BlockState state = level.getBlockState(pos);
				for (TextureAtlasSprite sprite : this.spritesFor(models, state, pos, hit.getDirection())) {
					sink.accept(sprite, texelsPerBlock);
				}
			}
		}
		return rays;
	}

	private TextureAtlasSprite[] spritesFor(final BlockStateModelSet models, final BlockState state, final BlockPos pos, final Direction face) {
		TextureAtlasSprite[][] byFace = this.spriteCache.get(state);
		if (byFace == null) {
			byFace = this.collect(models.get(state), state, pos);
			this.spriteCache.put(state, byFace);
		}
		TextureAtlasSprite[] sprites = byFace[face.ordinal()];
		return sprites.length > 0 ? sprites : byFace[6];
	}

	private TextureAtlasSprite[][] collect(final BlockStateModel model, final BlockState state, final BlockPos pos) {
		List<BlockStateModelPart> parts = new ArrayList<>();
		this.random.setSeed(state.getSeed(pos));
		model.collectParts(this.random, parts);

		List<List<TextureAtlasSprite>> byFace = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			byFace.add(new ArrayList<>());
		}
		for (BlockStateModelPart part : parts) {
			for (Direction direction : Direction.values()) {
				for (BakedQuad quad : part.getQuads(direction)) {
					addUnique(byFace.get(direction.ordinal()), quad.materialInfo().sprite());
				}
			}
			for (BakedQuad quad : part.getQuads(null)) {
				TextureAtlasSprite sprite = quad.materialInfo().sprite();
				addUnique(byFace.get(quad.direction().ordinal()), sprite);
				addUnique(byFace.get(6), sprite);
			}
		}
		if (byFace.get(6).isEmpty()) {
			addUnique(byFace.get(6), model.particleMaterial().sprite());
		}

		TextureAtlasSprite[][] result = new TextureAtlasSprite[7][];
		for (int i = 0; i < 7; i++) {
			result[i] = byFace.get(i).isEmpty() ? NO_SPRITES : byFace.get(i).toArray(NO_SPRITES);
		}
		return result;
	}

	private static void addUnique(final List<TextureAtlasSprite> list, final TextureAtlasSprite sprite) {
		if (!list.contains(sprite)) {
			list.add(sprite);
		}
	}
}
