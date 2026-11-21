package dev.winter.seedlod.gen;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * Per-thread structure generator and baker owned by one {@link TileGen}.
 *
 * <p>Holds a fast {@link ChunkGenerator} wrapper (answering height queries directly from the thread's bound density
 * samplers and buffer pool instead of vanilla's per-call {@code NoiseChunk} + {@code Aquifer} allocation), a concrete
 * {@link WorldGenLevel} stage (zero proxy reflection), and reusable mipping scratch arrays.
 */
final class StructureBaker {
	private static final int SCOUT_STEP = 16;
	private static final int MAX_HEIGHT_CACHE = 8192;
	private static final int NO_HEIGHT = Integer.MIN_VALUE;
	private static final Predicate<BlockState> MOTION_BLOCKING = Heightmap.Types.MOTION_BLOCKING.isOpaque();

	private final WorldgenView view;
	private final DensitySampler.Bound exactDensity;
	private final DensitySampler.Bound strideDensity;
	private final Climate.Sampler climate;
	private final BiomeResolver biomes;
	private final LevelHeightAccessor heightAccessor;
	private final HeightGenerator heightGenerator;
	private final Stage stage;
	private final BitSet touched = new BitSet();

	/** Per-thread caches of (blockX, blockZ) -> topmost solid block Y + 1 (exact for near levels, 4-block snapped for far). */
	private final Long2IntOpenHashMap exactSolidHeights = new Long2IntOpenHashMap();
	private final Long2IntOpenHashMap coarseSolidHeights = new Long2IntOpenHashMap();
	private boolean coarseMode;

	// Baking scratch (reused across structures on this thread).
	private final Long2IntOpenHashMap placed = new Long2IntOpenHashMap();
	private final Reference2IntOpenHashMap<BlockState> paletteMap = new Reference2IntOpenHashMap<>();
	private final List<@Nullable BlockState> paletteList = new ArrayList<>();
	private final IntArrayList beardsBuf = new IntArrayList();

	// Mipping scratch (reused across structures).
	private final Long2IntOpenHashMap mipGroups = new Long2IntOpenHashMap();
	private long[] curKeys = new long[0];
	private int[] curPalette = new int[0];
	private int[] curWeight = new int[0];
	private int[] curPrio3 = new int[0];
	private byte[] curLight = new byte[0];

	private long[] grpKeys = new long[0];
	private int[] grpCandP = new int[0];
	private int[] grpCandW = new int[0];
	private byte[] grpCandN = new byte[0];
	private int[] grpWeight = new int[0];
	private int[] grpPrio3 = new int[0];
	private byte[] grpLight = new byte[0];

	StructureBaker(WorldgenView view, DensitySampler.Bound exactDensity, DensitySampler.Bound strideDensity,
		Climate.Sampler climate, BiomeResolver biomes) {
		this.view = view;
		this.exactDensity = exactDensity;
		this.strideDensity = strideDensity;
		this.climate = climate;
		this.biomes = biomes;
		this.heightAccessor = LevelHeightAccessor.create(view.minY, view.height);
		this.heightGenerator = new HeightGenerator();
		this.stage = new Stage(view, biomes);
		this.exactSolidHeights.defaultReturnValue(NO_HEIGHT);
		this.coarseSolidHeights.defaultReturnValue(NO_HEIGHT);
		this.placed.defaultReturnValue(-1);
		this.paletteMap.defaultReturnValue(-1);
		this.mipGroups.defaultReturnValue(-1);
	}

	/** Stamps all surface structures overlapping {@code tile}. */
	void place(Tile tile) {
		Structures structures = this.view.structures;
		if (structures == null || structures.isEmpty()) return;
		this.touched.clear();
		structures.stampInto(tile, this, this.touched);
		for (int column = this.touched.nextSetBit(0); column >= 0; column = this.touched.nextSetBit(column + 1)) {
			tile.relight(column, this.view.seaLevel);
		}
	}

	void releaseScratch() {
		this.exactSolidHeights.clear();
		this.exactSolidHeights.trim();
		this.coarseSolidHeights.clear();
		this.coarseSolidHeights.trim();
		this.placed.clear();
		this.placed.trim();
		this.mipGroups.clear();
		this.mipGroups.trim();
		this.curKeys = new long[0];
		this.curPalette = new int[0];
		this.curWeight = new int[0];
		this.curPrio3 = new int[0];
		this.curLight = new byte[0];
		this.grpKeys = new long[0];
		this.grpCandP = new int[0];
		this.grpCandW = new int[0];
		this.grpCandN = new byte[0];
		this.grpWeight = new int[0];
		this.grpPrio3 = new int[0];
		this.grpLight = new byte[0];
	}

	/**
	 * Generates and bakes the structure (if any) starting at {@code (chunkX, chunkZ)} for {@code set}.
	 * When {@code coarseOnly} is true, drops levels 0..2 from the result so far-away structures use ~2 KB in cache.
	 */
	BakedStructure generateAndBake(Structures.VisibleSet set, int chunkX, int chunkZ, boolean coarseOnly) {
		Structures structures = this.view.structures;
		if (structures == null) return BakedStructure.EMPTY;
		this.coarseMode = coarseOnly;
		if (this.exactSolidHeights.size() > MAX_HEIGHT_CACHE) this.exactSolidHeights.clear();
		if (this.coarseSolidHeights.size() > MAX_HEIGHT_CACHE) this.coarseSolidHeights.clear();

		// Quick biome column check: reject chunks whose column has none of the set's biomes before running any entry.
		int centerX = (chunkX << 4) + 8, centerZ = (chunkZ << 4) + 8;
		if (!this.anyBiomeInColumn(set, centerX, centerZ)) return BakedStructure.EMPTY;

		ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
		StructureStart start = this.generateStart(set, chunkPos, structures);
		if (start == null || !start.isValid()) return BakedStructure.EMPTY;
		return this.bake(start, chunkPos, coarseOnly ? BakedStructure.COARSE_LEVEL : 0);
	}

	/** Checks every 16 blocks vertically for a biome accepted by at least one structure in {@code set}. */
	private boolean anyBiomeInColumn(Structures.VisibleSet set, int blockX, int blockZ) {
		int qx = blockX >> 2, qz = blockZ >> 2;
		int minQy = this.view.minY >> 2;
		int maxQy = (this.view.minY + this.view.height - 1) >> 2;
		// Check sea-level / surface region first (hits immediately in the common case).
		if (set.acceptsBiome(this.biomes.getNoiseBiome(qx, Math.clamp(this.view.seaLevel >> 2, minQy, maxQy), qz))) return true;
		for (int qy = minQy; qy <= maxQy; qy += 4) {
			if (set.acceptsBiome(this.biomes.getNoiseBiome(qx, qy, qz))) return true;
		}
		return false;
	}

	/** Mirrors vanilla {@code ChunkGenerator.createStructures}'s weighted selection exactly. */
	private @Nullable StructureStart generateStart(Structures.VisibleSet set, ChunkPos chunkPos, Structures structures) {
		List<StructureSet.StructureSelectionEntry> entries = set.entries;
		if (entries.size() == 1) {
			StructureSet.StructureSelectionEntry entry = entries.getFirst();
			if (!Structures.isVisibleStructure(entry.structure().value())) return null;
			return this.tryGenerate(entry.structure(), chunkPos, structures);
		}
		List<StructureSet.StructureSelectionEntry> remaining = new ArrayList<>(entries);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setLargeFeatureSeed(this.view.seed, chunkPos.x(), chunkPos.z());
		int totalWeight = 0;
		for (var entry : remaining) totalWeight += entry.weight();
		while (!remaining.isEmpty() && totalWeight > 0) {
			int roll = random.nextInt(totalWeight);
			int idx = 0;
			for (var entry : remaining) {
				roll -= entry.weight();
				if (roll < 0) break;
				idx++;
			}
			StructureSet.StructureSelectionEntry picked = remaining.get(idx);
			if (Structures.isVisibleStructure(picked.structure().value())) {
				StructureStart start = this.tryGenerate(picked.structure(), chunkPos, structures);
				if (start != null && start.isValid()) return start;
			}
			remaining.remove(idx);
			totalWeight -= picked.weight();
		}
		return null;
	}

	private @Nullable StructureStart tryGenerate(Holder<Structure> holder, ChunkPos chunkPos, Structures structures) {
		Structure structure = holder.value();
		try {
			StructureStart start = structure.generate(
				holder, this.view.dimension, this.view.registries, this.heightGenerator, this.view.biomes,
				this.climate, this.view.randomState, structures.templates, this.view.seed, chunkPos, 0,
				this.heightAccessor, structure.biomes()::contains
			);
			return start.isValid() ? start : null;
		} catch (RuntimeException e) {
			Hooks.report("A structure failed to generate on Seed LOD's terrain; skipping it where it does", e);
			return null;
		}
	}

	/** Places {@code start} once into {@link #stage}, builds its mip pyramid, and discards the mutable start. */
	private BakedStructure bake(StructureStart start, ChunkPos startChunk, int minKeepLevel) {
		BoundingBox startBox = start.getBoundingBox();
		BoundingBox placeBox = new BoundingBox(
			startBox.minX(), this.view.minY, startBox.minZ(),
			startBox.maxX(), this.view.minY + this.view.height - 1, startBox.maxZ()
		);

		this.stage.reset(startChunk);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
		random.setDecorationSeed(this.view.seed, startChunk.getMinBlockX(), startChunk.getMinBlockZ());

		try {
			// Passing null StructureManager is safe for all vanilla surface structures; guarded by catch for custom mods.
			start.placeInChunk(this.stage, null, this.heightGenerator, random, placeBox, startChunk);
		} catch (RuntimeException e) {
			Hooks.report("A structure failed to place on Seed LOD's terrain; skipping what failed", e);
		}

		if (this.stage.nonAirCount == 0) return BakedStructure.EMPTY;

		int minX = this.stage.minX, minY = this.stage.minY, minZ = this.stage.minZ;
		int maxX = this.stage.maxX, maxY = this.stage.maxY, maxZ = this.stage.maxZ;
		if (minX > maxX || minY > maxY || minZ > maxZ) return BakedStructure.EMPTY;

		// Skip structures completely buried underground (e.g. underground ruined portals).
		int minGround = this.minGroundAround(minX, minZ, maxX, maxZ);
		if (maxY < minGround - 6) return BakedStructure.EMPTY;

		// Collect rigid-piece footprints for BEARD_THIN/BEARD_BOX terrain mounds.
		int[] beards = this.collectBeards(start);

		@Nullable BlockState[] palette = this.paletteList.toArray(new BlockState[0]);
		int paletteSize = palette.length;
		byte[] priorities = new byte[paletteSize];
		byte[] emissions = new byte[paletteSize];
		for (int i = 1; i < paletteSize; i++) {
			BlockState state = palette[i];
			priorities[i] = blockPriority(state);
			emissions[i] = (byte) state.getLightEmission();
		}

		long[][] mips = new long[BakedStructure.LEVELS][];
		int count = this.placed.size();
		this.ensureCurCapacity(count);

		// Populate level 0 array (if needed) and feed non-air blocks into the bottom-up mip pyramid.
		long[] lvl0 = minKeepLevel == 0 ? new long[count] : null;
		int lvl0Len = 0;
		int curLen = 0;
		for (var it = this.placed.long2IntEntrySet().fastIterator(); it.hasNext(); ) {
			Long2IntMap.Entry entry = it.next();
			long posKey = entry.getLongKey();
			int pIdx = entry.getIntValue();
			int x = BlockPos.getX(posKey), y = BlockPos.getY(posKey), z = BlockPos.getZ(posKey);
			if (lvl0 != null) {
				int rx = x - minX, ry = y - minY, rz = z - minZ;
				if (rx >= 0 && rx <= BakedStructure.MAX_REL && ry >= 0 && ry <= BakedStructure.MAX_REL && rz >= 0 && rz <= BakedStructure.MAX_REL) {
					lvl0[lvl0Len++] = BakedStructure.pack(rx, ry, rz, pIdx, emissions[pIdx]);
				}
			}
			if (pIdx > 0 && priorities[pIdx] > 0) {
				this.curKeys[curLen] = posKey;
				this.curPalette[curLen] = pIdx;
				this.curWeight[curLen] = 1;
				this.curPrio3[curLen] = priorities[pIdx] == 3 ? 1 : 0;
				this.curLight[curLen] = emissions[pIdx];
				curLen++;
			}
		}
		if (lvl0 != null) mips[0] = lvl0Len == lvl0.length ? lvl0 : Arrays.copyOf(lvl0, lvl0Len);

		// Bottom-up 2x2x2 mipping: lvl 0 -> 1 -> 2 -> 3 -> 4.
		for (int lvl = 1; lvl < BakedStructure.LEVELS && curLen > 0; lvl++) {
			curLen = this.mipStep(curLen, priorities);
			if (lvl >= minKeepLevel) {
				mips[lvl] = this.emitMipLevel(lvl, curLen, minX, minY, minZ);
			}
		}

		return new BakedStructure(minX, minY, minZ, maxX, maxY, maxZ, palette, mips, beards, minKeepLevel);
	}

	/** Combines 2x2x2 children in {@code cur*} into parent voxels, writing the next level back into {@code cur*}. */
	private int mipStep(int curLen, byte[] priorities) {
		this.mipGroups.clear();
		int numGroups = 0;
		for (int i = 0; i < curLen; i++) {
			long k = this.curKeys[i];
			long parentKey = BlockPos.asLong(BlockPos.getX(k) >> 1, BlockPos.getY(k) >> 1, BlockPos.getZ(k) >> 1);
			int slot = this.mipGroups.get(parentKey);
			if (slot < 0) {
				slot = numGroups++;
				this.ensureGrpCapacity(numGroups);
				this.mipGroups.put(parentKey, slot);
				this.grpKeys[slot] = parentKey;
				this.grpCandN[slot] = 0;
				this.grpWeight[slot] = 0;
				this.grpPrio3[slot] = 0;
				this.grpLight[slot] = 0;
			}
			int pIdx = this.curPalette[i];
			int w = this.curWeight[i];
			this.grpWeight[slot] += w;
			this.grpPrio3[slot] += this.curPrio3[i];
			byte l = this.curLight[i];
			if (l > this.grpLight[slot]) this.grpLight[slot] = l;

			int base = slot << 3;
			int n = this.grpCandN[slot];
			boolean merged = false;
			for (int c = 0; c < n; c++) {
				if (this.grpCandP[base + c] == pIdx) {
					this.grpCandW[base + c] += w;
					merged = true;
					break;
				}
			}
			if (!merged && n < 8) {
				this.grpCandP[base + n] = pIdx;
				this.grpCandW[base + n] = w;
				this.grpCandN[slot] = (byte) (n + 1);
			}
		}

		this.ensureCurCapacity(numGroups);
		for (int slot = 0; slot < numGroups; slot++) {
			int base = slot << 3;
			int n = this.grpCandN[slot];
			int bestP = this.grpCandP[base];
			int bestPrio = priorities[bestP];
			int bestW = this.grpCandW[base];
			for (int c = 1; c < n; c++) {
				int p = this.grpCandP[base + c];
				int prio = priorities[p];
				int w = this.grpCandW[base + c];
				if (prio > bestPrio || (prio == bestPrio && w > bestW)) {
					bestP = p;
					bestPrio = prio;
					bestW = w;
				}
			}
			this.curKeys[slot] = this.grpKeys[slot];
			this.curPalette[slot] = bestP;
			this.curWeight[slot] = this.grpWeight[slot];
			this.curPrio3[slot] = this.grpPrio3[slot];
			this.curLight[slot] = this.grpLight[slot];
		}
		return numGroups;
	}

	private long[] emitMipLevel(int lvl, int count, int minX, int minY, int minZ) {
		int baseVx = minX >> lvl, baseVy = minY >> lvl, baseVz = minZ >> lvl;
		int minTotal = switch (lvl) {
			case 1 -> 1;
			case 2 -> 2;
			case 3 -> 12;
			default -> 48;
		};
		int minPrio3 = switch (lvl) {
			case 3 -> 8;
			case 4 -> 32;
			default -> minTotal;
		};
		long[] out = new long[count];
		int len = 0;
		for (int i = 0; i < count; i++) {
			if (this.curWeight[i] < minTotal && this.curPrio3[i] < minPrio3) continue;
			long k = this.curKeys[i];
			int rx = BlockPos.getX(k) - baseVx;
			int ry = BlockPos.getY(k) - baseVy;
			int rz = BlockPos.getZ(k) - baseVz;
			if (rx < 0 || rx > BakedStructure.MAX_REL || ry < 0 || ry > BakedStructure.MAX_REL || rz < 0 || rz > BakedStructure.MAX_REL) continue;
			out[len++] = BakedStructure.pack(rx, ry, rz, this.curPalette[i], this.curLight[i]);
		}
		return len == out.length ? out : Arrays.copyOf(out, len);
	}

	private int @Nullable [] collectBeards(StructureStart start) {
		TerrainAdjustment adjust = start.getStructure().terrainAdaptation();
		if (adjust != TerrainAdjustment.BEARD_THIN && adjust != TerrainAdjustment.BEARD_BOX) return null;
		this.beardsBuf.clear();
		for (StructurePiece piece : start.getPieces()) {
			if (piece instanceof PoolElementStructurePiece pool) {
				if (pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) continue;
				BoundingBox box = pool.getBoundingBox();
				this.beardsBuf.add(box.minX());
				this.beardsBuf.add(box.minZ());
				this.beardsBuf.add(box.maxX());
				this.beardsBuf.add(box.maxZ());
				this.beardsBuf.add(box.minY() + pool.getGroundLevelDelta());
			}
		}
		return this.beardsBuf.isEmpty() ? null : this.beardsBuf.toIntArray();
	}

	/** Minimum terrain solid height across the center and 4 corners of a footprint. */
	private int minGroundAround(int minX, int minZ, int maxX, int maxZ) {
		int cx = (minX + maxX) >> 1, cz = (minZ + maxZ) >> 1;
		int min = this.solidHeight(cx, cz);
		min = Math.min(min, this.solidHeight(minX, minZ));
		min = Math.min(min, this.solidHeight(maxX, minZ));
		min = Math.min(min, this.solidHeight(minX, maxZ));
		min = Math.min(min, this.solidHeight(maxX, maxZ));
		return min;
	}

	/**
	 * Block priority for coarse LOD downsampling:
	 * 3 = primary structural blocks (opaque blocks, stairs, slabs, walls),
	 * 2 = secondary solid/translucent blocks (fences, glass, doors, leaves, paths),
	 * 1 = tiny decorations (torches, flowers, carpets, signs),
	 * 0 = air, water, and technical blocks (ignored at coarse levels).
	 */
	private static byte blockPriority(BlockState state) {
		if (state.isAir() || isTechnical(state)) return 0;
		if (!state.getFluidState().isEmpty() && state.getFluidState().is(Fluids.WATER)) return 0;
		Block block = state.getBlock();
		if (state.canOcclude() || block instanceof StairBlock || block instanceof SlabBlock || block instanceof WallBlock) return 3;
		if (MOTION_BLOCKING.test(state)) return 2;
		return 1;
	}

	private static boolean isTechnical(BlockState state) {
		return state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.JIGSAW) || state.is(Blocks.BARRIER);
	}

	/**
	 * Fast cached solid surface height (topmost solid block Y + 1, or {@code minY} if empty) for column {@code (x, z)}.
	 * Scouts around sea level in 16-block steps then binary-searches the 16-block bracket with {@code sampleValue}
	 * (zero {@code DensityVolume} allocations). At far levels ({@link #coarseMode}), snaps to 8-block cells with
	 * {@link #strideDensity}; at near levels, evaluates {@link #exactDensity} at vanilla's 4-block cell corners and
	 * bilinearly interpolates inside each cell.
	 */
	int solidHeight(int blockX, int blockZ) {
		if (this.coarseMode) {
			return this.cachedCorner(this.coarseSolidHeights, this.strideDensity, blockX & ~7, blockZ & ~7);
		}
		int x0 = blockX & ~3, z0 = blockZ & ~3;
		int fx = blockX - x0, fz = blockZ - z0;
		int h00 = this.cachedCorner(this.exactSolidHeights, this.exactDensity, x0, z0);
		if (fx == 0 && fz == 0) return h00;
		int h10 = fx == 0 ? h00 : this.cachedCorner(this.exactSolidHeights, this.exactDensity, x0 + 4, z0);
		int h01 = fz == 0 ? h00 : this.cachedCorner(this.exactSolidHeights, this.exactDensity, x0, z0 + 4);
		int h11 = (fx == 0 || fz == 0) ? (fx == 0 ? h01 : h10) : this.cachedCorner(this.exactSolidHeights, this.exactDensity, x0 + 4, z0 + 4);
		int hx0 = h00 * (4 - fx) + h10 * fx;
		int hx1 = h01 * (4 - fx) + h11 * fx;
		return (hx0 * (4 - fz) + hx1 * fz + 8) >> 4;
	}

	private int cachedCorner(Long2IntOpenHashMap cache, DensitySampler.Bound density, int x, int z) {
		long key = (long) x << 32 | (z & 0xFFFFFFFFL);
		int cached = cache.get(key);
		if (cached != NO_HEIGHT) return cached;
		int result = this.findSurface(density, x, z, this.view.minY, this.view.minY + this.view.height - 1);
		cache.put(key, result);
		return result;
	}

	private int findSurface(DensitySampler.Bound density, int x, int z, int minY, int maxY) {
		// Most terrain sits within [seaLevel - 32, seaLevel + 112]; probe that band top-down first.
		int bandLo = Math.max(minY, this.view.seaLevel - 32);
		int bandHi = Math.min(maxY, this.view.seaLevel + 112);

		int solidY = Integer.MIN_VALUE;
		int airY = maxY + 1;
		if (density.sampleValue(x, bandHi, z) > 0.0F) {
			// Mountain above bandHi: step upward until air or maxY.
			solidY = bandHi;
			for (int y = bandHi + SCOUT_STEP; y <= maxY; y += SCOUT_STEP) {
				if (density.sampleValue(x, y, z) <= 0.0F) {
					airY = y;
					break;
				}
				solidY = y;
			}
			if (airY > maxY) {
				if (density.sampleValue(x, maxY, z) > 0.0F) return maxY + 1;
				airY = maxY;
			}
		} else {
			airY = bandHi;
			for (int y = bandHi - SCOUT_STEP; y >= bandLo; y -= SCOUT_STEP) {
				if (density.sampleValue(x, y, z) > 0.0F) {
					solidY = y;
					break;
				}
				airY = y;
			}
			if (solidY == Integer.MIN_VALUE) {
				// Below bandLo (deep ocean, void, or low dimension like The End): scan down to minY.
				for (int y = bandLo - SCOUT_STEP; y >= minY; y -= SCOUT_STEP) {
					if (density.sampleValue(x, y, z) > 0.0F) {
						solidY = y;
						break;
					}
					airY = y;
				}
				if (solidY == Integer.MIN_VALUE) {
					if (density.sampleValue(x, minY, z) <= 0.0F) return minY;
					solidY = minY;
				}
			}
		}

		// Binary search inside [solidY, airY): density(solidY) > 0, density(airY) <= 0.
		int lo = solidY, hi = airY;
		while (hi - lo > 1) {
			int mid = (lo + hi) >> 1;
			if (density.sampleValue(x, mid, z) > 0.0F) lo = mid;
			else hi = mid;
		}
		return lo + 1;
	}

	int height(Heightmap.Types type, int blockX, int blockZ) {
		int solid = this.solidHeight(blockX, blockZ);
		if (!this.view.defaultFluid.isAir() && type.isOpaque().test(this.view.defaultFluid)) {
			return Math.max(solid, this.view.seaLevel);
		}
		return solid;
	}

	private void ensureCurCapacity(int needed) {
		if (this.curKeys.length >= needed) return;
		int cap = Math.max(256, Integer.highestOneBit(needed - 1) << 1);
		this.curKeys = new long[cap];
		this.curPalette = new int[cap];
		this.curWeight = new int[cap];
		this.curPrio3 = new int[cap];
		this.curLight = new byte[cap];
	}

	private void ensureGrpCapacity(int needed) {
		if (this.grpKeys.length >= needed) return;
		int cap = Math.max(256, Integer.highestOneBit(needed - 1) << 1);
		this.grpKeys = Arrays.copyOf(this.grpKeys, cap);
		this.grpCandP = Arrays.copyOf(this.grpCandP, cap << 3);
		this.grpCandW = Arrays.copyOf(this.grpCandW, cap << 3);
		this.grpCandN = Arrays.copyOf(this.grpCandN, cap);
		this.grpWeight = Arrays.copyOf(this.grpWeight, cap);
		this.grpPrio3 = Arrays.copyOf(this.grpPrio3, cap);
		this.grpLight = Arrays.copyOf(this.grpLight, cap);
	}

	/**
	 * Lightweight {@link ChunkGenerator} delegating everything to {@code view.generator} except height/column queries,
	 * which run on this thread's cached density samplers.
	 */
	private final class HeightGenerator extends ChunkGenerator {
		HeightGenerator() {
			super(view.biomes);
		}

		@Override
		protected MapCodec<? extends ChunkGenerator> codec() {
			throw new UnsupportedOperationException();
		}

		@Override
		public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor height, RandomState random) {
			return StructureBaker.this.height(type, x, z);
		}

		@Override
		public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
			int minY = Math.max(view.minY, height.getMinY());
			int maxY = Math.min(view.minY + view.height, height.getMaxY() + 1);
			int len = Math.max(0, maxY - minY);
			BlockState[] col = new BlockState[len];
			int solidTop = StructureBaker.this.solidHeight(x, z);
			BlockState air = Blocks.AIR.defaultBlockState();
			for (int i = 0; i < len; i++) {
				int y = minY + i;
				if (y < solidTop) col[i] = view.defaultBlock;
				else if (y < view.seaLevel) col[i] = view.defaultFluid;
				else col[i] = air;
			}
			return new NoiseColumn(minY, col);
		}

		@Override
		public int getGenDepth() {
			return view.height;
		}

		@Override
		public int getSeaLevel() {
			return view.seaLevel;
		}

		@Override
		public int getMinY() {
			return view.minY;
		}

		@Override
		public void spawnOriginalMobs(WorldGenRegion region) {
		}

		@Override
		public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState random,
			StructureManager structures, BiomeManager biomes, WorldGenRegion region, java.util.Set<Holder<Biome>> present) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos, SamplerContext context) {
		}
	}

	/** Concrete {@link StagedLevel} for baking a single structure into {@link #placed}. */
	private final class Stage extends StagedLevel {
		int minX, minY, minZ, maxX, maxY, maxZ;
		int nonAirCount;

		Stage(WorldgenView view, BiomeResolver biomes) {
			super(view, biomes);
		}

		void reset(ChunkPos chunk) {
			placed.clear();
			paletteMap.clear();
			paletteList.clear();
			paletteList.add(null); // index 0 = air
			this.minX = this.minY = this.minZ = Integer.MAX_VALUE;
			this.maxX = this.maxY = this.maxZ = Integer.MIN_VALUE;
			this.nonAirCount = 0;
			this.random.setSeed(this.view.seed ^ ((long) chunk.x() * 341873128712L + (long) chunk.z() * 132897987541L));
		}

		private int paletteIndex(BlockState state) {
			int idx = paletteMap.getInt(state);
			if (idx >= 0) return idx;
			if (paletteList.size() > BakedStructure.MAX_PALETTE) return -1;
			idx = paletteList.size();
			paletteList.add(state);
			paletteMap.put(state, idx);
			return idx;
		}

		@Override
		public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
			int x = pos.getX(), y = pos.getY(), z = pos.getZ();
			if (y < this.view.minY || y >= this.view.minY + this.view.height) return false;
			if (isTechnical(state)) return true;
			long key = BlockPos.asLong(x, y, z);
			if (state.isAir()) {
				int prev = placed.put(key, 0);
				if (prev > 0) this.nonAirCount--;
				return true;
			}
			int idx = this.paletteIndex(state);
			if (idx < 0) return false;
			int prev = placed.put(key, idx);
			if (prev <= 0) this.nonAirCount++;
			if (x < this.minX) this.minX = x;
			if (x > this.maxX) this.maxX = x;
			if (y < this.minY) this.minY = y;
			if (y > this.maxY) this.maxY = y;
			if (z < this.minZ) this.minZ = z;
			if (z > this.maxZ) this.maxZ = z;
			return true;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			int y = pos.getY();
			if (y < this.view.minY || y >= this.view.minY + this.view.height) return Blocks.VOID_AIR.defaultBlockState();
			int idx = placed.get(BlockPos.asLong(pos.getX(), y, pos.getZ()));
			if (idx >= 0) {
				BlockState s = paletteList.get(idx);
				return s == null ? Blocks.AIR.defaultBlockState() : s;
			}
			int solidTop = solidHeight(pos.getX(), pos.getZ());
			if (y < solidTop) return this.view.defaultBlock;
			if (y < this.view.seaLevel) return this.view.defaultFluid;
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			int y = pos.getY();
			if (y < this.view.minY || y >= this.view.minY + this.view.height) return Fluids.EMPTY.defaultFluidState();
			int idx = placed.get(BlockPos.asLong(pos.getX(), y, pos.getZ()));
			if (idx >= 0) {
				BlockState s = paletteList.get(idx);
				return s == null ? Fluids.EMPTY.defaultFluidState() : s.getFluidState();
			}
			if (y >= this.view.seaLevel || this.view.defaultFluid.isAir()) return Fluids.EMPTY.defaultFluidState();
			if (coarseMode) return this.view.defaultFluid.getFluidState();
			return y >= solidHeight(pos.getX(), pos.getZ()) ? this.view.defaultFluid.getFluidState() : Fluids.EMPTY.defaultFluidState();
		}

		@Override
		public int getHeight(Heightmap.Types type, int x, int z) {
			return StructureBaker.this.height(type, x, z);
		}

		@Override
		public boolean ensureCanWrite(BlockPos pos) {
			return pos.getY() >= this.view.minY && pos.getY() < this.view.minY + this.view.height;
		}

		@Override
		public @Nullable ServerLevel getLevel() {
			// Never hand out the live ServerLevel to background worker threads.
			return null;
		}
	}
}
