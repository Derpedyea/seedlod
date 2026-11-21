package dev.winter.seedlod.gen;

import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/**
 * Grows vanilla's own trees into generated terrain. Every chunk's tree features run with the seeds vanilla's
 * decoration gives them (one per chunk, then one per feature index), against a stand-in level that reads and writes
 * the tile, so trees come out where and as real chunks will have them. Other decoration (lakes, rocks, flowers) isn't
 * run, so the odd tree that would collide with one can differ.
 *
 * <p>Above level 0 the trees see the terrain at the tile's resolution, and a voxel takes a trunk over leaves and
 * leaves over air, as Voxy's own coarser levels show real trees.
 */
public final class RealTrees {
	static final int MAX_LEVEL = 2;
	/** Blocks of terrain generated around a group, so trees standing just outside it still reach in. 8 matched barely more. */
	static final int MARGIN = 4;
	/** Set while planting without a live level, for mods that look at it (see the client's StreamsSettingsMixin). */
	public static final ThreadLocal<Boolean> NO_LEVEL = ThreadLocal.withInitial(() -> false);

	/** Marks the level trees and structures are staged in, for code that has to tell it from a real one ({@code SeedLodApi.isSeedLodLevel}). */
	public interface Staged {
	}

	private static final int UNKNOWN = Integer.MIN_VALUE;

	private final WorldgenView view;
	private final int minSectionY;
	/** Tree features with vanilla's biome filter swapped for one reading {@link BiomeFeatures}. */
	private final Map<PlacedFeature, PlacedFeature> filtered = new IdentityHashMap<>();
	private final Stage stage;
	private final FeaturePlacer placer;
	private final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
	private final Set<Holder<Biome>> areaBiomes = new ReferenceOpenHashSet<>();
	private final Set<Holder<Biome>> nearby = new ReferenceOpenHashSet<>();
	private final List<BiomeFeatures.Indexed> activeTrees = new ArrayList<>();

	RealTrees(WorldgenView view, BiomeResolver biomes) {
		this.view = view;
		this.minSectionY = SectionPos.blockToSectionCoord(view.minY);
		this.stage = new Stage(view, biomes);
		this.placer = new FeaturePlacer(this.stage, view.generator);
	}

	void releaseScratch() {
		Arrays.fill(this.stage.tops, null);
		this.stage.area = null;
	}

	void plant(Tile area) {
		// Pre-collect biomes present in this area so barren areas (ocean, desert, beach) skip the chunk loop completely,
		// and active areas only iterate tree features that can actually grow here.
		this.areaBiomes.clear();
		for (Holder<Biome> biome : area.biomes) if (biome != null) this.areaBiomes.add(biome);
		if (this.areaBiomes.isEmpty()) return;

		this.activeTrees.clear();
		for (BiomeFeatures.Indexed tree : this.view.features.trees) {
			if (this.anyHas(this.areaBiomes, tree.feature())) this.activeTrees.add(tree);
		}
		if (this.activeTrees.isEmpty()) return;

		this.stage.bind(area);
		int minX = this.stage.minX, minZ = this.stage.minZ, span = this.stage.span;
		boolean singleBiome = this.areaBiomes.size() == 1;
		int scanStep = Math.max(4, this.stage.step);
		NO_LEVEL.set(this.view.level == null);
		try {
			for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(minZ + span - 1, 16); cz++)
				for (int cx = Math.floorDiv(minX, 16); cx <= Math.floorDiv(minX + span - 1, 16); cx++) {
					Set<Holder<Biome>> present;
					if (singleBiome) {
						present = this.areaBiomes;
					} else {
						// Vanilla only runs the features of biomes in and around the chunk; its biome filter does the rest.
						this.nearby.clear();
						for (int z = cz * 16 - 16; z < cz * 16 + 32; z += scanStep)
							for (int x = cx * 16 - 16; x < cx * 16 + 32; x += scanStep) {
								Holder<Biome> biome = this.stage.surfaceBiome(x, z);
								if (biome != null) this.nearby.add(biome);
							}
						present = this.nearby;
					}
					BlockPos origin = new BlockPos(cx * 16, this.minSectionY * 16, cz * 16);
					long decorationSeed = this.random.setDecorationSeed(this.view.seed, origin.getX(), origin.getZ());
					for (BiomeFeatures.Indexed tree : this.activeTrees) {
						if (!singleBiome && !this.anyHas(present, tree.feature())) continue;
						this.random.setFeatureSeed(decorationSeed, tree.index(), BiomeFeatures.STEP);
						try {
							this.placer.place(this.filtered(tree.feature()), this.random, origin);
						} catch (RuntimeException e) {
							Hooks.report("A tree feature failed on Seed LOD's terrain; skipping it where it does", e);
						}
					}
				}
		} finally {
			NO_LEVEL.set(false);
			this.stage.area = null;
		}
	}

	private boolean anyHas(Set<Holder<Biome>> biomes, PlacedFeature feature) {
		for (Holder<Biome> biome : biomes) if (this.view.features.has(biome, feature)) return true;
		return false;
	}

	private PlacedFeature filtered(PlacedFeature feature) {
		return this.filtered.computeIfAbsent(feature, f -> {
			List<PlacementModifier> placement = f.placement().stream()
				.map(m -> m instanceof BiomeFilter ? (PlacementModifier) new GrowsHere(this.view.features, f) : m).toList();
			return new PlacedFeature(f.feature(), placement);
		});
	}

	/** Vanilla's biome filter, reading the biome's features from {@link BiomeFeatures} rather than the generator. */
	private record GrowsHere(BiomeFeatures features, PlacedFeature feature) implements PlacementFilter {
		@Override
		public boolean shouldPlace(PlacementContext context, RandomSource random, BlockPos origin) {
			return this.features.has(context.getLevel().getBiome(origin), this.feature);
		}

		@Override
		public MapCodec<BiomeFilter> codec() {
			return BiomeFilter.CODEC;
		}
	}

	/** Concrete {@link StagedLevel} over the current tile's voxels; reused across groups on this thread. */
	private static final class Stage extends StagedLevel {
		Tile area;
		int step, minX, minZ, span;
		/** Cached {@link #getHeight} per heightmap type and column, resized lazily. */
		final int[][] tops = new int[Heightmap.Types.values().length][];

		Stage(WorldgenView view, BiomeResolver biomes) {
			super(view, biomes);
		}

		void bind(Tile area) {
			this.area = area;
			this.step = 1 << area.lvl;
			this.minX = area.voxelX * this.step;
			this.minZ = area.voxelZ * this.step;
			this.span = area.size * this.step;
			int columns = area.size * area.size;
			for (int i = 0; i < this.tops.length; i++) {
				int[] cached = this.tops[i];
				if (cached != null) {
					if (cached.length < columns) this.tops[i] = null;
					else Arrays.fill(cached, 0, columns, UNKNOWN);
				}
			}
		}

		Holder<Biome> surfaceBiome(int x, int z) {
			int column = this.area.column(x, z);
			return column < 0 ? null : this.area.biomes[column];
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			int index = this.area.blockIndex(pos.getX(), pos.getY(), pos.getZ());
			BlockState state = index < 0 ? null : this.area.states[index];
			return state == null ? Blocks.AIR.defaultBlockState() : state;
		}

		@Override
		public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
			int index = this.area.blockIndex(pos.getX(), pos.getY(), pos.getZ());
			if (index < 0) return false;
			BlockState current = this.area.states[index];
			if (this.area.lvl > 0) {
				// Several blocks share a voxel: never clear one, and let a trunk show through leaves.
				if (state.isAir() || current != null && !(current.is(BlockTags.LEAVES) && state.is(BlockTags.LOGS))) return true;
			}
			this.area.states[index] = state.isAir() ? null : state;
			this.area.light[index] = 15;
			int column = index / this.area.voxelsY;
			for (int[] tops : this.tops) if (tops != null) tops[column] = UNKNOWN;
			return true;
		}

		@Override
		public int getHeight(Heightmap.Types type, int x, int z) {
			int column = this.area.column(x, z);
			if (column < 0) return this.view.minY;
			int ordinal = type.ordinal();
			int[] tops = this.tops[ordinal];
			if (tops == null) {
				tops = this.tops[ordinal] = new int[this.area.size * this.area.size];
				Arrays.fill(tops, UNKNOWN);
			}
			int top = tops[column];
			if (top != UNKNOWN) return top;
			return tops[column] = this.area.height(column, type.isOpaque(), this.view.minY);
		}

		@Override
		public boolean ensureCanWrite(BlockPos pos) {
			return this.area.blockIndex(pos.getX(), pos.getY(), pos.getZ()) >= 0;
		}

		@Override
		public ServerLevel getLevel() {
			if (this.view.level != null) return this.view.level;
			throw new UnsupportedOperationException("Seed LOD's terrain has no live ServerLevel");
		}
	}
}
