package dev.spritestream;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Owns the high-res pool and decides, every few frames, which block textures get which resolution.
 *
 * <p>Flow per frame (render thread, outside any render pass):
 * <ol>
 *   <li>{@link ScreenProbe} measures the texels-per-block each visible sprite needs.</li>
 *   <li>Sprites are ranked by screen coverage and given the resolution they need while the pool has room;
 *       when it runs out, later sprites drop a step, down to the base atlas.</li>
 *   <li>Sprites that need more get a block in the pool (evicting sprites holding more than they now
 *       need) and a background decode of exactly that resolution plus mips, for colour and, when a PBR
 *       shader pack is active, the {@code _n} and {@code _s} maps too.</li>
 *   <li>Decoded mips are uploaded under a per-frame byte budget, then the sprite table is updated so the
 *       shaders start sampling the block, fading in from the base texture.</li>
 * </ol>
 */
public final class StreamManager {
	public static final BindGroupLayout BIND_GROUP = BindGroupLayout.builder()
		.withUniform("ss_IdMap", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("ss_Table", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("ss_Color", UniformType.COMBINED_IMAGE_SAMPLER)
		.build();
	private static final int TABLE_TEXELS_PER_SPRITE = 4;
	private static final int TABLE_SPRITES_PER_ROW = 256;
	private static final int ASSIGN_INTERVAL_FRAMES = 3;
	private static final int PBR_CHECK_INTERVAL_FRAMES = 30;
	/** Leave room for fragmentation when sharing the pool between resolutions. */
	private static final float FILL_TARGET = 0.85F;

	/** Debug switch: sample only the base atlas, as if nothing were streamed. */
	public static volatile boolean forceBase;

	private static @Nullable StreamManager instance;

	private final ExecutorService decoder;
	private final ConcurrentLinkedQueue<DecodeJob> decoded = new ConcurrentLinkedQueue<>();
	private final ArrayDeque<DecodeJob> uploads = new ArrayDeque<>();
	private final AtomicInteger inFlight = new AtomicInteger();
	private final ScreenProbe probe = new ScreenProbe();

	private StreamedSprite[] sprites = new StreamedSprite[0];
	private final Map<TextureAtlasSprite, StreamedSprite> byAtlasSprite = new IdentityHashMap<>();
	private @Nullable PoolPage page;
	private boolean pbrPlanes;
	private int generation;

	private @Nullable GpuTexture dummyTexture;
	private @Nullable GpuTextureView dummyView;
	private @Nullable GpuTexture idMapTexture;
	private @Nullable GpuTextureView idMapView;
	private @Nullable GpuTexture tableTexture;
	private @Nullable GpuTextureView tableView;
	private @Nullable ByteBuffer tableMirror;
	private int tableRows;
	private boolean tableDirty;
	private @Nullable GpuSampler poolSampler;
	private @Nullable GpuSampler nearestSampler;

	private long frame;
	private long lastWindowRotate = System.nanoTime();
	private long lastStats = System.nanoTime();
	private long uploadedBytes;
	private int raysThisInterval;

	private StreamManager() {
		AtomicInteger threadId = new AtomicInteger();
		this.decoder = Executors.newFixedThreadPool(SpriteStreamMod.config.decodeThreads, r -> {
			Thread thread = new Thread(r, "SpriteStream decode " + threadId.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY + 1);
			return thread;
		});
	}

	public static StreamManager get() {
		if (instance == null) {
			instance = new StreamManager();
		}
		return instance;
	}

	// ---------------------------------------------------------------- atlas lifecycle

	/** Rebuilds everything for a freshly stitched block atlas. Render thread. */
	public void onBlockAtlasUploaded(final SpriteLoader.Preparations preparations) {
		this.generation++;
		this.dropInFlight();
		this.closePage();
		this.probe.clearCache();
		this.byAtlasSprite.clear();
		this.ensureCommonResources();

		SpriteStreamConfig config = SpriteStreamMod.config;
		Map<Identifier, BaseAtlasLoader.Source> sources = BaseAtlasLoader.isActive() ? BaseAtlasLoader.sources() : Map.of();
		ResourceManager resources = Minecraft.getInstance().getResourceManager();

		List<StreamedSprite> list = new ArrayList<>();
		list.add(null); // id 0 means "not streamed"
		int withPbr = 0;
		for (TextureAtlasSprite sprite : preparations.regions().values()) {
			BaseAtlasLoader.Source source = sources.get(sprite.contents().name());
			if (source == null || sprite.isAnimated() || list.size() > 65535) {
				continue;
			}
			int largest = Math.max(source.width(), source.height());
			int maxResolution = Math.min(config.maxResolution, Integer.highestOneBit(largest));
			if (maxResolution < PoolPage.CELL || maxResolution <= config.baseResolution) {
				continue;
			}
			Identifier name = sprite.contents().name();
			Resource normal = pbrResource(resources, name, "_n");
			Resource specular = pbrResource(resources, name, "_s");
			if (normal != null || specular != null) {
				withPbr++;
			}
			StreamedSprite streamed = new StreamedSprite(
				list.size(), name, source.resource(), normal, specular, source.width(), source.height(), maxResolution,
				sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()
			);
			list.add(streamed);
			this.byAtlasSprite.put(sprite, streamed);
		}
		this.sprites = list.toArray(new StreamedSprite[0]);

		this.buildIdMap(preparations.width(), preparations.height());
		this.buildTable();
		this.pbrPlanes = SpriteStreamMod.isIrisPackActive();
		this.createPage();
		SpriteStreamMod.LOGGER.info("[SpriteStream] {} streamable block textures ({} with PBR maps)", this.sprites.length - 1, withPbr);
	}

	private static @Nullable Resource pbrResource(final ResourceManager resources, final Identifier sprite, final String suffix) {
		Optional<Resource> resource = resources.getResource(sprite.withPath(path -> "textures/" + path + suffix + ".png"));
		return resource.orElse(null);
	}

	private void createPage() {
		SpriteStreamConfig config = SpriteStreamMod.config;
		int planes = this.pbrPlanes ? 3 : 1;
		this.page = PoolPage.create((long) config.vramBudgetMiB << 20, planes, config.maxResolution, config.baseResolution);
		if (this.page == null) {
			SpriteStreamMod.LOGGER.warn("[SpriteStream] VRAM budget too small for even one 128x block; streaming off");
			return;
		}
		SpriteStreamMod.LOGGER.info(
			"[SpriteStream] Pool {}x{} up to {}x, {} plane(s), {} MiB (budget {} MiB)",
			this.page.width, this.page.height, this.page.maxResolution, planes, this.page.allocatedBytes() >> 20, config.vramBudgetMiB
		);
	}

	private void closePage() {
		if (this.page != null) {
			this.page.close();
			this.page = null;
		}
		for (int i = 1; i < this.sprites.length; i++) {
			StreamedSprite s = this.sprites[i];
			s.residentResolution = 0;
			s.residentBlock = -1;
			s.pendingResolution = 0;
			s.pendingBlock = -1;
			s.effectiveResolution = 0;
		}
		if (this.tableMirror != null) {
			for (int i = 1; i < this.sprites.length; i++) {
				this.writeTableEntry(i, this.sprites[i]);
			}
			this.tableDirty = true;
		}
	}

	/** Jobs still decoding are dropped when they come back, because their generation is then stale. */
	private void dropInFlight() {
		for (DecodeJob job : this.uploads) {
			job.free();
			this.inFlight.decrementAndGet();
		}
		this.uploads.clear();
		DecodeJob job;
		while ((job = this.decoded.poll()) != null) {
			job.free();
			this.inFlight.decrementAndGet();
		}
	}

	private void buildIdMap(final int atlasWidth, final int atlasHeight) {
		int cell = 16;
		for (int i = 1; i < this.sprites.length; i++) {
			StreamedSprite s = this.sprites[i];
			int bits = Math.round(s.u0 * atlasWidth) | Math.round(s.v0 * atlasHeight) | Math.round(s.u1 * atlasWidth) | Math.round(s.v1 * atlasHeight);
			while (cell > 1 && (bits & (cell - 1)) != 0) {
				cell >>= 1;
			}
		}
		int mapW = Math.max(1, (atlasWidth + cell - 1) / cell);
		int mapH = Math.max(1, (atlasHeight + cell - 1) / cell);

		try (NativeImage map = new NativeImage(mapW, mapH, true)) {
			for (int i = 1; i < this.sprites.length; i++) {
				StreamedSprite s = this.sprites[i];
				int x0 = Math.round(s.u0 * atlasWidth) / cell;
				int y0 = Math.round(s.v0 * atlasHeight) / cell;
				int x1 = Math.round(s.u1 * atlasWidth) / cell;
				int y1 = Math.round(s.v1 * atlasHeight) / cell;
				int pixel = 0xFF000000 | ((i >> 8) & 0xFF) << 8 | (i & 0xFF);
				for (int y = y0; y < y1; y++) {
					for (int x = x0; x < x1; x++) {
						map.setPixelABGR(x, y, pixel);
					}
				}
			}
			if (this.idMapView != null) {
				this.idMapView.close();
				this.idMapTexture.close();
			}
			GpuDevice device = RenderSystem.getDevice();
			this.idMapTexture = device.createTexture(
				"spritestream id map", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, mapW, mapH, 1, 1
			);
			this.idMapView = device.createTextureView(this.idMapTexture);
			device.createCommandEncoder().writeToTexture(this.idMapTexture, map);
		}
		if (cell < 4) {
			SpriteStreamMod.LOGGER.warn("[SpriteStream] Atlas sprites are not aligned to 4 texels; id map uses {}-texel cells", cell);
		}
	}

	private void buildTable() {
		int rows = Math.max(1, (this.sprites.length + TABLE_SPRITES_PER_ROW - 1) / TABLE_SPRITES_PER_ROW);
		int width = TABLE_SPRITES_PER_ROW * TABLE_TEXELS_PER_SPRITE;
		if (this.tableView != null) {
			this.tableView.close();
			this.tableTexture.close();
		}
		if (this.tableMirror != null) {
			MemoryUtil.memFree(this.tableMirror);
		}
		this.tableRows = rows;
		this.tableMirror = MemoryUtil.memCalloc(width * rows * 16);
		GpuDevice device = RenderSystem.getDevice();
		this.tableTexture = device.createTexture(
			"spritestream sprite table", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA32_FLOAT, width, rows, 1, 1
		);
		this.tableView = device.createTextureView(this.tableTexture);
		for (int i = 1; i < this.sprites.length; i++) {
			this.writeTableEntry(i, this.sprites[i]);
		}
		this.tableDirty = true;
	}

	/**
	 * Table layout, four RGBA32F texels per sprite:
	 * [u0 v0 u1 v1] [resident, pageU, pageV, fade] [extentU, extentV, 1/W, 1/H] [maxLod, hasNormal, hasSpecular, 0].
	 */
	private void writeTableEntry(final int id, final StreamedSprite s) {
		ByteBuffer buf = this.tableMirror;
		int row = id / TABLE_SPRITES_PER_ROW;
		int base = (row * TABLE_SPRITES_PER_ROW * TABLE_TEXELS_PER_SPRITE + (id % TABLE_SPRITES_PER_ROW) * TABLE_TEXELS_PER_SPRITE) * 16;
		buf.putFloat(base, s.u0).putFloat(base + 4, s.v0).putFloat(base + 8, s.u1).putFloat(base + 12, s.v1);
		PoolPage page = this.page;
		if (page == null || s.residentResolution == 0) {
			for (int i = 16; i < 64; i += 4) {
				buf.putFloat(base + i, 0.0F);
			}
			return;
		}
		float texW = page.width;
		float texH = page.height;
		int largest = Math.max(s.sourceWidth, s.sourceHeight);
		int w = Math.max(1, (int) ((long) s.sourceWidth * s.residentResolution / largest));
		int h = Math.max(1, (int) ((long) s.sourceHeight * s.residentResolution / largest));
		int maxLod = Integer.numberOfTrailingZeros(s.residentResolution) - Integer.numberOfTrailingZeros(page.baseResolution);
		buf.putFloat(base + 16, 1.0F)
			.putFloat(base + 20, PoolPage.x(s.residentBlock) / texW)
			.putFloat(base + 24, PoolPage.y(s.residentBlock) / texH)
			.putFloat(base + 28, s.fade);
		buf.putFloat(base + 32, w / texW).putFloat(base + 36, h / texH).putFloat(base + 40, 1.0F / texW).putFloat(base + 44, 1.0F / texH);
		boolean pbr = page.planeCount > 1;
		buf.putFloat(base + 48, maxLod)
			.putFloat(base + 52, pbr && s.normalResource != null ? 1.0F : 0.0F)
			.putFloat(base + 56, pbr && s.specularResource != null ? 1.0F : 0.0F)
			.putFloat(base + 60, 0.0F);
	}

	private void ensureCommonResources() {
		if (this.dummyTexture != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		this.dummyTexture = device.createTexture(
			"spritestream dummy", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1
		);
		this.dummyView = device.createTextureView(this.dummyTexture);
		try (NativeImage zero = new NativeImage(1, 1, true)) {
			device.createCommandEncoder().writeToTexture(this.dummyTexture, zero);
		}
		int anisotropy = Math.max(1, Math.min(16, device.getDeviceInfo().limits().maxAnisotropy()));
		this.poolSampler = device.createSampler(
			AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, anisotropy, OptionalDouble.empty()
		);
		this.nearestSampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		if (this.tableTexture == null) {
			this.buildTable();
		}
	}

	// ---------------------------------------------------------------- per frame

	/** Runs once per frame on the render thread, before the level is drawn. */
	public void frame() {
		if (!RenderSystem.isOnRenderThread()) {
			return;
		}
		this.ensureCommonResources();
		this.frame++;
		Minecraft minecraft = Minecraft.getInstance();
		SpriteStreamConfig config = SpriteStreamMod.config;
		long now = System.nanoTime();

		// Normal and specular planes only cost VRAM while a shader pack can read them.
		if (this.frame % PBR_CHECK_INTERVAL_FRAMES == 0 && this.sprites.length > 1) {
			boolean wantPbr = SpriteStreamMod.isIrisPackActive();
			if (wantPbr != this.pbrPlanes) {
				this.generation++;
				this.dropInFlight();
				this.closePage();
				this.pbrPlanes = wantPbr;
				this.createPage();
			}
		}

		if (this.page != null && this.sprites.length > 1 && minecraft.level != null) {
			if (now - this.lastWindowRotate > (long) (config.evictDelaySeconds * 0.5e9)) {
				this.lastWindowRotate = now;
				for (int i = 1; i < this.sprites.length; i++) {
					StreamedSprite s = this.sprites[i];
					s.wantPrevious = s.wantCurrent;
					s.wantCurrent = 0.0F;
					s.hitsPrevious = s.hitsCurrent;
					s.hitsCurrent = 0;
				}
			}

			this.raysThisInterval += this.probe.run(minecraft, config, (sprite, texelsPerBlock) -> {
				StreamedSprite s = this.byAtlasSprite.get(sprite);
				if (s != null) {
					if (texelsPerBlock > s.wantCurrent) {
						s.wantCurrent = texelsPerBlock;
					}
					s.hitsCurrent++;
				}
			});

			if (this.frame % ASSIGN_INTERVAL_FRAMES == 0) {
				this.assign();
			}
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		this.collectDecoded();
		this.processUploads(encoder, (long) config.uploadMiBPerFrame << 20);
		this.updateFades(now);
		if (this.tableDirty && this.tableTexture != null) {
			this.tableMirror.clear();
			encoder.writeToTexture(this.tableTexture, this.tableMirror, 0, 0, 0, 0, TABLE_SPRITES_PER_ROW * TABLE_TEXELS_PER_SPRITE, this.tableRows);
			this.tableDirty = false;
		}

		if (config.logStats && now - this.lastStats > 5_000_000_000L) {
			this.logStats(now);
		}
		TestHarness.frame(minecraft);
	}

	private int targetResolution(final StreamedSprite s) {
		float want = s.wanted();
		int base = SpriteStreamMod.config.baseResolution;
		if (want <= base || this.page == null) {
			return 0;
		}
		int needed = (int) Math.ceil(want);
		int resolution = Integer.highestOneBit(needed - 1) << 1; // next power of two >= needed
		resolution = Math.max(PoolPage.CELL, resolution);
		return Math.min(resolution, Math.min(s.maxResolution, this.page.maxResolution));
	}

	private void assign() {
		PoolPage page = this.page;
		List<StreamedSprite> candidates = new ArrayList<>();
		int[] target = new int[this.sprites.length];
		for (int i = 1; i < this.sprites.length; i++) {
			StreamedSprite s = this.sprites[i];
			s.effectiveResolution = 0;
			target[i] = this.targetResolution(s);
			if (target[i] > 0) {
				candidates.add(s);
			}
		}
		// Most screen coverage first: those textures are what the player is actually looking at.
		candidates.sort((a, b) -> {
			int byHits = Integer.compare(b.hits(), a.hits());
			return byHits != 0 ? byHits : Integer.compare(target[b.id], target[a.id]);
		});

		long capacity = (long) (page.totalCells() * FILL_TARGET);
		long used = 0;
		for (StreamedSprite s : candidates) {
			int resolution = target[s.id];
			while (resolution >= PoolPage.CELL && used + PoolPage.cellsFor(resolution) > capacity) {
				resolution >>= 1;
			}
			if (resolution < PoolPage.CELL) {
				continue;
			}
			s.effectiveResolution = resolution;
			used += PoolPage.cellsFor(resolution);
		}

		int maxInFlight = SpriteStreamMod.config.decodeThreads * 2;
		for (StreamedSprite s : candidates) {
			int resolution = s.effectiveResolution;
			if (resolution == 0 || s.residentResolution >= resolution || s.pendingResolution == resolution) {
				continue;
			}
			if (this.inFlight.get() >= maxInFlight) {
				break;
			}
			if (s.pendingResolution > 0) {
				this.cancelPending(s);
			}
			int block = page.findFree(resolution);
			if (block < 0) {
				block = this.evictFor(resolution, s);
			}
			if (block < 0) {
				continue;
			}
			page.mark(block, resolution, s.id);
			s.pendingResolution = resolution;
			s.pendingBlock = block;
			this.submit(new DecodeJob(this.generation, s, page, resolution, block));
		}
	}

	/** Clears the cheapest block whose owners all hold more than they currently need. */
	private int evictFor(final int resolution, final StreamedSprite requester) {
		PoolPage page = this.page;
		int block = page.findVictimBlock(
			resolution,
			owner -> {
				StreamedSprite o = this.sprites[owner];
				return o != requester && o.effectiveResolution < Math.max(o.residentResolution, o.pendingResolution);
			},
			owner -> this.sprites[owner].hits() + 1L
		);
		if (block < 0) {
			return -1;
		}
		for (int owner : page.ownersIn(block, resolution)) {
			StreamedSprite o = this.sprites[owner];
			if (o.pendingResolution > 0 && overlaps(o.pendingBlock, o.pendingResolution, block, resolution)) {
				this.cancelPending(o);
			}
			if (o.residentResolution > 0 && overlaps(o.residentBlock, o.residentResolution, block, resolution)) {
				page.mark(o.residentBlock, o.residentResolution, 0);
				o.residentResolution = 0;
				o.residentBlock = -1;
				this.writeTableEntry(o.id, o);
				this.tableDirty = true;
			}
		}
		return block;
	}

	private static boolean overlaps(final int a, final int aSize, final int b, final int bSize) {
		int ax = PoolPage.x(a), ay = PoolPage.y(a), bx = PoolPage.x(b), by = PoolPage.y(b);
		return ax < bx + bSize && bx < ax + aSize && ay < by + bSize && by < ay + aSize;
	}

	private void cancelPending(final StreamedSprite s) {
		if (this.page != null && s.pendingResolution > 0) {
			this.page.mark(s.pendingBlock, s.pendingResolution, 0);
		}
		s.pendingResolution = 0;
		s.pendingBlock = -1;
	}

	private void submit(final DecodeJob job) {
		this.inFlight.incrementAndGet();
		this.decoder.execute(() -> {
			try {
				if (job.isCurrent()) {
					job.decode();
				}
			} catch (Throwable t) {
				SpriteStreamMod.LOGGER.warn("[SpriteStream] Failed to decode {}", job.sprite.name, t);
				job.failed = true;
			}
			this.decoded.add(job);
		});
	}

	private void collectDecoded() {
		DecodeJob job;
		while ((job = this.decoded.poll()) != null) {
			if (job.failed || !job.isCurrent() || job.planes == null) {
				if (job.isCurrent()) {
					this.cancelPending(job.sprite);
				}
				job.free();
				this.inFlight.decrementAndGet();
				continue;
			}
			this.uploads.add(job);
		}
	}

	private void processUploads(final CommandEncoder encoder, final long budget) {
		long spent = 0;
		while (!this.uploads.isEmpty() && spent < budget) {
			DecodeJob job = this.uploads.peek();
			if (!job.isCurrent()) {
				this.uploads.poll();
				job.free();
				this.inFlight.decrementAndGet();
				continue;
			}
			spent += job.uploadSome(encoder, budget - spent);
			if (job.isUploaded()) {
				this.uploads.poll();
				this.finish(job);
				job.free();
				this.inFlight.decrementAndGet();
			}
		}
		this.uploadedBytes += spent;
	}

	private void finish(final DecodeJob job) {
		StreamedSprite s = job.sprite;
		boolean fromBase = s.residentResolution == 0;
		if (s.residentResolution > 0) {
			this.page.mark(s.residentBlock, s.residentResolution, 0);
		}
		s.residentResolution = job.resolution;
		s.residentBlock = job.block;
		s.pendingResolution = 0;
		s.pendingBlock = -1;
		if (fromBase && SpriteStreamMod.config.fadeSeconds > 0.0F) {
			s.fade = 0.0F;
			s.fadeStartNanos = System.nanoTime();
		} else {
			s.fade = 1.0F;
		}
		this.writeTableEntry(s.id, s);
		this.tableDirty = true;
	}

	private void updateFades(final long now) {
		float duration = SpriteStreamMod.config.fadeSeconds;
		for (int i = 1; i < this.sprites.length; i++) {
			StreamedSprite s = this.sprites[i];
			if (s.fade < 1.0F && s.residentResolution > 0) {
				s.fade = duration <= 0.0F ? 1.0F : Math.min(1.0F, (now - s.fadeStartNanos) / (duration * 1e9F));
				this.writeTableEntry(s.id, s);
				this.tableDirty = true;
			}
		}
	}

	/** Logs how many visible sprites want each resolution, labelled for test runs. */
	void logUsage(final String label) {
		Map<Integer, Integer> wanting = new java.util.TreeMap<>();
		for (int i = 1; i < this.sprites.length; i++) {
			int resolution = this.targetResolution(this.sprites[i]);
			if (resolution > 0) {
				wanting.merge(resolution, 1, Integer::sum);
			}
		}
		SpriteStreamMod.LOGGER.info("[SpriteStream] [{}] sprites wanting more than base, by resolution: {}", label, wanting);
	}

	private void logStats(final long now) {
		float seconds = (now - this.lastStats) / 1e9F;
		Map<Integer, Integer> resident = new java.util.TreeMap<>();
		for (int i = 1; i < this.sprites.length; i++) {
			if (this.sprites[i].residentResolution > 0) {
				resident.merge(this.sprites[i].residentResolution, 1, Integer::sum);
			}
		}
		PoolPage page = this.page;
		SpriteStreamMod.LOGGER.info(
			"[SpriteStream] resident {} | pool {}% full, {} plane(s) | in flight {} | upload {} MiB/s | {} rays/s",
			resident,
			page == null ? 0 : page.usedCells() * 100 / page.totalCells(),
			page == null ? 0 : page.planeCount,
			this.inFlight.get(),
			String.format("%.1f", (this.uploadedBytes / 1048576.0F) / seconds),
			(int) (this.raysThisInterval / seconds)
		);
		this.lastStats = now;
		this.uploadedBytes = 0;
		this.raysThisInterval = 0;
	}

	// ---------------------------------------------------------------- binding

	/** Binds the pool to a Sodium terrain draw. Must match {@link #BIND_GROUP}. */
	public void bind(final RenderPass pass) {
		if (this.dummyView == null) {
			return;
		}
		pass.setUniform("ss_IdMap", this.idMapView(), this.nearestSampler);
		pass.setUniform("ss_Table", this.tableView(), this.nearestSampler);
		pass.setUniform("ss_Color", this.planeView(PoolPage.PLANE_COLOR), this.poolSampler);
	}

	public GpuTextureView idMapView() {
		return this.idMapView != null && !forceBase ? this.idMapView : this.dummyView;
	}

	public GpuTextureView tableView() {
		return this.tableView != null ? this.tableView : this.dummyView;
	}

	public GpuTextureView planeView(final int plane) {
		PoolPage page = this.page;
		return page != null && plane < page.planeCount ? page.views[plane] : this.dummyView;
	}

	public @Nullable GpuSampler poolSampler() {
		return this.poolSampler;
	}

	public @Nullable GpuSampler nearestSampler() {
		return this.nearestSampler;
	}

	// ---------------------------------------------------------------- decode jobs

	private final class DecodeJob {
		final int generation;
		final StreamedSprite sprite;
		final PoolPage page;
		final int resolution;
		final int block;
		volatile boolean failed;
		/** Per plane (null when the plane is skipped), per mip level. */
		ByteBuffer @Nullable [][] planes;
		int[] widths;
		int[] heights;
		int uploadPlane;
		int uploadLevel;
		int uploadRow;

		DecodeJob(final int generation, final StreamedSprite sprite, final PoolPage page, final int resolution, final int block) {
			this.generation = generation;
			this.sprite = sprite;
			this.page = page;
			this.resolution = resolution;
			this.block = block;
		}

		boolean isCurrent() {
			return this.generation == StreamManager.this.generation
				&& this.page == StreamManager.this.page
				&& this.sprite.pendingResolution == this.resolution
				&& this.sprite.pendingBlock == this.block;
		}

		/** Worker thread: decode the sources and build exactly this resolution's mip chain for each plane. */
		void decode() throws Exception {
			int levels = Integer.numberOfTrailingZeros(this.resolution) - Integer.numberOfTrailingZeros(this.page.baseResolution) + 1;
			int largest = Math.max(this.sprite.sourceWidth, this.sprite.sourceHeight);
			int w = Math.max(1, (int) ((long) this.sprite.sourceWidth * this.resolution / largest));
			int h = Math.max(1, (int) ((long) this.sprite.sourceHeight * this.resolution / largest));
			int[] ws = new int[levels];
			int[] hs = new int[levels];
			for (int level = 0; level < levels; level++) {
				ws[level] = Math.max(1, w >> level);
				hs[level] = Math.max(1, h >> level);
			}
			this.widths = ws;
			this.heights = hs;

			ByteBuffer[][] out = new ByteBuffer[this.page.planeCount][];
			out[PoolPage.PLANE_COLOR] = this.decodePlane(this.sprite.resource, true);
			if (this.page.planeCount > 1) {
				out[PoolPage.PLANE_NORMAL] = this.sprite.normalResource == null ? null : this.decodePlane(this.sprite.normalResource, false);
				out[PoolPage.PLANE_SPECULAR] = this.sprite.specularResource == null ? null : this.decodePlane(this.sprite.specularResource, false);
			}
			this.planes = out;
		}

		/**
		 * Colour is averaged alpha-weighted (alpha is coverage). Normal and specular maps keep data in alpha
		 * (height, emission), so their channels are averaged independently.
		 */
		private ByteBuffer[] decodePlane(final Resource resource, final boolean alphaIsCoverage) throws Exception {
			NativeImage image;
			try (InputStream in = resource.open()) {
				image = NativeImage.read(in);
			}
			try {
				ByteBuffer[] levels = new ByteBuffer[this.widths.length];
				for (int level = 0; level < levels.length; level++) {
					int lw = this.widths[level];
					int lh = this.heights[level];
					levels[level] = MemoryUtil.memAlloc(lw * lh * 4);
					long src = level == 0 ? image.getPointer() : MemoryUtil.memAddress(levels[level - 1]);
					int sw = level == 0 ? image.getWidth() : this.widths[level - 1];
					int sh = level == 0 ? image.getHeight() : this.heights[level - 1];
					if (sw == lw && sh == lh) {
						MemoryUtil.memCopy(src, MemoryUtil.memAddress(levels[level]), (long) lw * lh * 4L);
					} else if (alphaIsCoverage) {
						ImageOps.downsample(src, sw, sh, MemoryUtil.memAddress(levels[level]), lw, lh);
					} else {
						ImageOps.downsamplePlain(src, sw, sh, MemoryUtil.memAddress(levels[level]), lw, lh);
					}
				}
				return levels;
			} finally {
				image.close();
			}
		}

		/** Render thread: upload rows until {@code budget} bytes are spent; returns bytes uploaded. */
		long uploadSome(final CommandEncoder encoder, final long budget) {
			long spent = 0;
			int baseX = PoolPage.x(this.block);
			int baseY = PoolPage.y(this.block);
			while (this.uploadPlane < this.planes.length && spent < budget) {
				ByteBuffer[] levels = this.planes[this.uploadPlane];
				if (levels == null || this.uploadLevel >= levels.length) {
					this.uploadPlane++;
					this.uploadLevel = 0;
					this.uploadRow = 0;
					continue;
				}
				int w = this.widths[this.uploadLevel];
				int h = this.heights[this.uploadLevel];
				long rowBytes = (long) w * 4L;
				int rows = (int) Math.max(1, Math.min(h - this.uploadRow, (budget - spent) / rowBytes));
				ByteBuffer slice = MemoryUtil.memSlice(levels[this.uploadLevel], (int) (this.uploadRow * rowBytes), (int) (rows * rowBytes));
				encoder.writeToTexture(
					this.page.textures[this.uploadPlane], slice, this.uploadLevel, 0,
					baseX >> this.uploadLevel, (baseY >> this.uploadLevel) + this.uploadRow, w, rows
				);
				spent += rows * rowBytes;
				this.uploadRow += rows;
				if (this.uploadRow >= h) {
					this.uploadRow = 0;
					this.uploadLevel++;
				}
			}
			return spent;
		}

		boolean isUploaded() {
			return this.planes != null && this.uploadPlane >= this.planes.length;
		}

		void free() {
			if (this.planes != null) {
				for (ByteBuffer[] levels : this.planes) {
					if (levels != null) {
						for (ByteBuffer level : levels) {
							if (level != null) {
								MemoryUtil.memFree(level);
							}
						}
					}
				}
				this.planes = null;
			}
		}
	}
}
