package dev.winter.seedlod.gen;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.DimensionOriginStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

/**
 * Surface structure lookup and shared bounded cache for one dimension.
 *
 * <p>Filters the dimension's {@link StructureSet}s to visible surface structures (skipping mineshafts, strongholds,
 * buried treasure, ancient cities, trial chambers, trail ruins and similar underground structures), steps grid cells
 * directly in O(area / spacing²) rather than scanning chunks, and caches every candidate start chunk once across all
 * worker threads and LOD levels.
 */
public final class Structures {
	/** Vanilla's 8-chunk structure reference radius: the farthest a piece can reach from its start chunk. */
	private static final int SEARCH_RADIUS_CHUNKS = 8;
	/** Max full-res (levels 0..4) structures retained; evicted ones downgrade to coarse-only (levels 3..4, ~2 KB each). */
	private static final int MAX_FULL_CACHE = 128;
	/** Max coarse-only + empty candidate entries retained in RAM (~8 MB worst case). */
	private static final int MAX_COARSE_CACHE = 16_384;
	private static final int LOCK_STRIPES = 64;

	static final class VisibleSet {
		final int index;
		final StructurePlacement placement;
		final List<StructureSet.StructureSelectionEntry> entries;
		private final Set<Holder<Biome>> allBiomes;

		VisibleSet(int index, StructureSet set, Set<Holder<Biome>> allBiomes) {
			this.index = index;
			this.placement = set.placement();
			this.entries = set.structures();
			this.allBiomes = allBiomes;
		}

		boolean acceptsBiome(Holder<Biome> biome) {
			return this.allBiomes.contains(biome);
		}
	}

	final ChunkGeneratorStructureState state;
	final StructureTemplateManager templates;
	private final List<VisibleSet> sets;
	private final ReentrantLock[] bakeLocks = new ReentrantLock[LOCK_STRIPES];

	/** Bounded LRU cache of full-detail structures (levels 0..4). Evicted entries downgrade into {@link #coarseCache}. */
	private final Map<Long, BakedStructure> fullCache;
	/** Lock-free cache of coarse-only (levels 3..4) structures and {@link BakedStructure#EMPTY} misses. */
	private final ConcurrentHashMap<Long, BakedStructure> coarseCache = new ConcurrentHashMap<>(2048);

	public Structures(ChunkGeneratorStructureState state, StructureTemplateManager templates) {
		this.state = state;
		this.templates = templates;
		for (int i = 0; i < LOCK_STRIPES; i++) this.bakeLocks[i] = new ReentrantLock();

		List<VisibleSet> visible = new ArrayList<>();
		for (Holder<StructureSet> holder : state.possibleStructureSets()) {
			StructureSet set = holder.value();
			Set<Holder<Biome>> biomes = new ReferenceOpenHashSet<>();
			boolean anyVisible = false;
			for (StructureSet.StructureSelectionEntry entry : set.structures()) {
				Structure structure = entry.structure().value();
				if (!isVisibleStructure(structure)) continue;
				anyVisible = true;
				for (Holder<Biome> b : structure.biomes()) biomes.add(b);
			}
			if (anyVisible && !biomes.isEmpty()) {
				visible.add(new VisibleSet(visible.size(), set, biomes));
			}
		}
		this.sets = List.copyOf(visible);

		this.fullCache = Collections.synchronizedMap(new LinkedHashMap<>(MAX_FULL_CACHE + 16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, BakedStructure> eldest) {
				if (this.size() <= MAX_FULL_CACHE) return false;
				BakedStructure dropped = eldest.getValue();
				if (dropped != BakedStructure.EMPTY) putCoarse(eldest.getKey(), dropped.downgradeToCoarse());
				return true;
			}
		});
	}

	private void putCoarse(Long key, BakedStructure value) {
		if (this.coarseCache.size() >= MAX_COARSE_CACHE) this.coarseCache.clear();
		this.coarseCache.put(key, value);
	}

	boolean isEmpty() {
		return this.sets.isEmpty();
	}

	/** Surface structures only: underground steps and buried/encapsulated structures can never be seen in LODs. */
	static boolean isVisibleStructure(Structure structure) {
		GenerationStep.Decoration step = structure.step();
		if (step == GenerationStep.Decoration.UNDERGROUND_STRUCTURES
			|| step == GenerationStep.Decoration.UNDERGROUND_DECORATION
			|| step == GenerationStep.Decoration.UNDERGROUND_ORES
			|| step == GenerationStep.Decoration.STRONGHOLDS) {
			return false;
		}
		TerrainAdjustment adjust = structure.terrainAdaptation();
		return adjust != TerrainAdjustment.BURY && adjust != TerrainAdjustment.ENCAPSULATE;
	}

	/** Finds every visible structure that can overlap {@code tile} and stamps it at {@code tile.lvl}. */
	void stampInto(Tile tile, StructureBaker baker, BitSet touched) {
		int lvl = tile.lvl;
		int areaMinX = tile.voxelX << lvl;
		int areaMinZ = tile.voxelZ << lvl;
		int areaMaxX = ((tile.voxelX + tile.size) << lvl) - 1;
		int areaMaxZ = ((tile.voxelZ + tile.size) << lvl) - 1;

		int minChunkX = (areaMinX >> 4) - SEARCH_RADIUS_CHUNKS;
		int maxChunkX = (areaMaxX >> 4) + SEARCH_RADIUS_CHUNKS;
		int minChunkZ = (areaMinZ >> 4) - SEARCH_RADIUS_CHUNKS;
		int maxChunkZ = (areaMaxZ >> 4) + SEARCH_RADIUS_CHUNKS;

		long seed = this.state.getLevelSeed();
		for (VisibleSet set : this.sets) {
			StructurePlacement placement = set.placement;
			if (placement instanceof RandomSpreadStructurePlacement spread) {
				int spacing = spread.spacing();
				if (spacing <= 0) continue;
				int gx0 = Math.floorDiv(minChunkX, spacing), gx1 = Math.floorDiv(maxChunkX, spacing);
				int gz0 = Math.floorDiv(minChunkZ, spacing), gz1 = Math.floorDiv(maxChunkZ, spacing);
				for (int gz = gz0; gz <= gz1; gz++) {
					for (int gx = gx0; gx <= gx1; gx++) {
						ChunkPos candidate = spread.getPotentialStructureChunk(seed, gx * spacing, gz * spacing);
						int cx = candidate.x(), cz = candidate.z();
						if (cx < minChunkX || cx > maxChunkX || cz < minChunkZ || cz > maxChunkZ) continue;
						if (!placement.isStructureChunk(this.state, cx, cz)) continue;
						this.resolveAndStamp(set, cx, cz, tile, areaMinX, areaMinZ, areaMaxX, areaMaxZ, baker, touched);
					}
				}
			} else if (placement instanceof ConcentricRingsStructurePlacement rings) {
				List<ChunkPos> positions = this.state.getRingPositionsFor(rings);
				if (positions == null) continue;
				for (ChunkPos pos : positions) {
					int cx = pos.x(), cz = pos.z();
					if (cx < minChunkX || cx > maxChunkX || cz < minChunkZ || cz > maxChunkZ) continue;
					if (!placement.isStructureChunk(this.state, cx, cz)) continue;
					this.resolveAndStamp(set, cx, cz, tile, areaMinX, areaMinZ, areaMaxX, areaMaxZ, baker, touched);
				}
			} else if (placement instanceof DimensionOriginStructurePlacement) {
				ChunkPos origin = this.state.getDimensionOrigin();
				int cx = origin.x(), cz = origin.z();
				if (cx >= minChunkX && cx <= maxChunkX && cz >= minChunkZ && cz <= maxChunkZ
					&& placement.isStructureChunk(this.state, cx, cz)) {
					this.resolveAndStamp(set, cx, cz, tile, areaMinX, areaMinZ, areaMaxX, areaMaxZ, baker, touched);
				}
			} else if (lvl <= 2) {
				// Custom modded placement type with unknown grid spacing: scan chunks only at near levels.
				for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
					for (int cx = minChunkX; cx <= maxChunkX; cx++) {
						if (placement.isStructureChunk(this.state, cx, cz)) {
							this.resolveAndStamp(set, cx, cz, tile, areaMinX, areaMinZ, areaMaxX, areaMaxZ, baker, touched);
						}
					}
				}
			}
		}
	}

	private void resolveAndStamp(VisibleSet set, int cx, int cz, Tile tile,
		int areaMinX, int areaMinZ, int areaMaxX, int areaMaxZ, StructureBaker baker, BitSet touched) {
		BakedStructure baked = this.getOrBake(set, cx, cz, tile.lvl, baker);
		if (baked.intersects(areaMinX, areaMinZ, areaMaxX, areaMaxZ)) {
			baked.stamp(tile, touched);
		}
	}

	private BakedStructure getOrBake(VisibleSet set, int cx, int cz, int neededLvl, StructureBaker baker) {
		Long key = cacheKey(set.index, cx, cz);
		BakedStructure hit = this.lookup(key, neededLvl);
		if (hit != null) return hit;

		// Striped lock prevents two threads from baking the same structure simultaneously.
		ReentrantLock lock = this.bakeLocks[(int) (mix(key) & (LOCK_STRIPES - 1))];
		lock.lock();
		try {
			hit = this.lookup(key, neededLvl);
			if (hit != null) return hit;
			boolean coarseOnly = neededLvl >= BakedStructure.COARSE_LEVEL;
			BakedStructure baked = baker.generateAndBake(set, cx, cz, coarseOnly);
			if (baked == BakedStructure.EMPTY || coarseOnly) {
				this.putCoarse(key, baked);
			} else {
				this.fullCache.put(key, baked);
				this.coarseCache.remove(key);
			}
			return baked;
		} finally {
			lock.unlock();
		}
	}

	private @Nullable BakedStructure lookup(Long key, int neededLvl) {
		BakedStructure full = this.fullCache.get(key);
		if (full != null) return full;
		BakedStructure coarse = this.coarseCache.get(key);
		if (coarse != null && coarse.hasLevel(neededLvl)) return coarse;
		return null;
	}

	private static long cacheKey(int setIndex, int cx, int cz) {
		return ((long) (setIndex & 0xFF) << 56) | (((long) cx & 0xFFFFFFFL) << 28) | ((long) cz & 0xFFFFFFFL);
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
