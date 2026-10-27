package dev.winter.seedlod.gen;

import com.mojang.serialization.MapCodec;
import dev.winter.seedlod.SeedLod;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
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
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
	/** Set while planting without a live level, for mods that look at it (see the client's StreamsSettingsMixin). */
	public static final ThreadLocal<Boolean> NO_LEVEL = ThreadLocal.withInitial(() -> false);

	/** Marks the level trees are planted in, for code that has to tell it from a real one. */
	public interface Staged {
	}

	private final WorldgenView view;
	private final int minSectionY;
	/** Tree features with vanilla's biome filter swapped for one reading {@link BiomeFeatures}. */
	private final Map<PlacedFeature, PlacedFeature> filtered = new IdentityHashMap<>();

	RealTrees(WorldgenView view) {
		this.view = view;
		this.minSectionY = SectionPos.blockToSectionCoord(view.minY);
	}

	void plant(Tile area, BiomeResolver biomes) {
		Stage stage = new Stage(area, new BiomeManager(biomes, BiomeManager.obfuscateSeed(this.view.seed)));
		FeaturePlacer placer = new FeaturePlacer(stage.level, this.view.generator);
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
		Set<Holder<Biome>> nearby = new ReferenceOpenHashSet<>();
		int minX = stage.minX, minZ = stage.minZ, span = stage.span;
		NO_LEVEL.set(this.view.level == null);
		try {
			for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(minZ + span - 1, 16); cz++)
				for (int cx = Math.floorDiv(minX, 16); cx <= Math.floorDiv(minX + span - 1, 16); cx++) {
					// Vanilla only runs the features of biomes in and around the chunk; its biome filter does the rest.
					nearby.clear();
					for (int z = cz * 16 - 16; z < cz * 16 + 32; z += 4)
						for (int x = cx * 16 - 16; x < cx * 16 + 32; x += 4) {
							Holder<Biome> biome = stage.surfaceBiome(x, z);
							if (biome != null) nearby.add(biome);
						}
					BlockPos origin = new BlockPos(cx * 16, this.minSectionY * 16, cz * 16);
					long decorationSeed = random.setDecorationSeed(this.view.seed, origin.getX(), origin.getZ());
					for (BiomeFeatures.Indexed tree : this.view.features.trees) {
						if (!this.anyHas(nearby, tree.feature())) continue;
						random.setFeatureSeed(decorationSeed, tree.index(), BiomeFeatures.STEP);
						try {
							placer.place(this.filtered(tree.feature()), random, origin);
						} catch (RuntimeException e) {
							if (REPORTED.add(e.toString())) SeedLod.LOG.warn("A tree feature failed on Seed LOD's terrain; skipping it where it does", e);
						}
					}
				}
		} finally {
			NO_LEVEL.set(false);
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

	private static final int UNKNOWN = Integer.MIN_VALUE;

	/** The level features see: the tile's voxels in block coordinates, and nothing outside it. */
	private final class Stage implements InvocationHandler {
		final Tile area;
		final int step, shift, minX, minZ, span;
		final BiomeManager biomes;
		final RandomSource random = RandomSource.create(0);
		final WorldGenLevel level;
		/** Cached {@link #height} per heightmap type and column. */
		final int[][] tops = new int[Heightmap.Types.values().length][];

		Stage(Tile area, BiomeManager biomes) {
			this.area = area;
			this.step = 1 << area.lvl;
			this.shift = area.lvl;
			this.minX = area.voxelX * this.step;
			this.minZ = area.voxelZ * this.step;
			this.span = area.size * this.step;
			this.biomes = biomes;
			this.level = (WorldGenLevel) Proxy.newProxyInstance(RealTrees.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class, Staged.class}, this);
		}

		/** Column index for block x, z, or -1 outside the tile. */
		int column(int x, int z) {
			int vx = (x - this.minX) >> this.shift, vz = (z - this.minZ) >> this.shift;  // floorDiv by step
			return vx < 0 || vz < 0 || vx >= this.area.size || vz >= this.area.size ? -1 : vx + vz * this.area.size;
		}

		/** Voxel index for a block, or -1 outside the tile. */
		int index(BlockPos pos) {
			int column = this.column(pos.getX(), pos.getZ());
			int vy = (pos.getY() >> this.shift) - this.area.voxelMinY;
			return column < 0 || vy < 0 || vy >= this.area.voxelsY ? -1 : vy + column * this.area.voxelsY;
		}

		Holder<Biome> surfaceBiome(int x, int z) {
			int column = this.column(x, z);
			return column < 0 ? null : this.area.biomes[column];
		}

		BlockState get(BlockPos pos) {
			int index = this.index(pos);
			BlockState state = index < 0 ? null : this.area.states[index];
			return state == null ? Blocks.AIR.defaultBlockState() : state;
		}

		boolean set(BlockPos pos, BlockState state) {
			int index = this.index(pos);
			if (index < 0) return false;
			BlockState current = this.area.states[index];
			if (this.area.lvl > 0) {
				// Several blocks share a voxel: never clear one, and let a trunk show through leaves.
				if (state.isAir() || current != null && !(current.is(BlockTags.LEAVES) && state.is(BlockTags.LOGS))) return true;
			}
			this.area.states[index] = state.isAir() ? null : state;
			this.area.light[index] = 15;
			for (int[] tops : this.tops) if (tops != null) tops[index / this.area.voxelsY] = UNKNOWN;
			return true;
		}

		/** Like a chunk's heightmap: the block above the highest one {@code type} counts. */
		int height(Heightmap.Types type, int x, int z) {
			int column = this.column(x, z);
			if (column < 0) return view.minY;
			// Features ask for the same columns over and over; remember each answer until a block is set there.
			int[] tops = this.tops[type.ordinal()];
			if (tops == null) {
				tops = this.tops[type.ordinal()] = new int[this.area.size * this.area.size];
				Arrays.fill(tops, UNKNOWN);
			}
			int top = tops[column];
			if (top != UNKNOWN) return top;
			top = view.minY;
			int base = column * this.area.voxelsY;
			Predicate<BlockState> counts = type.isOpaque();
			for (int y = this.area.voxelsY - 1; y >= 0; y--) {
				BlockState state = this.area.states[base + y];
				if (state != null && counts.test(state)) {
					top = (this.area.voxelMinY + y + 1) * this.step;
					break;
				}
			}
			return tops[column] = top;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			int arity = args == null ? 0 : args.length;
			switch (method.getName()) {
				case "getBlockState":
					return this.get((BlockPos) args[0]);
				case "getFluidState":
					return this.get((BlockPos) args[0]).getFluidState();
				case "isStateAtPosition":
					return ((Predicate<BlockState>) args[1]).test(this.get((BlockPos) args[0]));
				case "isFluidAtPosition":
					return ((Predicate<Object>) args[1]).test(this.get((BlockPos) args[0]).getFluidState());
				case "setBlock":
					return this.set((BlockPos) args[0], (BlockState) args[1]);
				case "removeBlock", "destroyBlock":
					return this.set((BlockPos) args[0], Blocks.AIR.defaultBlockState());
				case "getHeight":
					if (arity == 3) return this.height((Heightmap.Types) args[0], (int) args[1], (int) args[2]);
					if (arity == 0) return view.height;
					break;
				case "getMinY":
					if (arity == 0) return view.minY;
					break;
				case "getSeaLevel":
					return view.seaLevel;
				case "getLevel":
					if (view.level != null) return view.level;
					break;
				case "registryAccess":
					return view.registries;
				case "getBrightness":
					// Features run before light, so assume open sky and no block light.
					return args[0] == LightLayer.SKY ? 15 : 0;
				case "getRawBrightness":
					return Math.max(0, 15 - (int) args[1]);
				case "canSeeSky":
					return true;
				case "getSeed":
					return view.seed;
				case "getRandom":
					return this.random;
				case "getBiome":
					if (arity == 1) return this.biomes.getBiome((BlockPos) args[0]);
					break;
				case "getBlockEntity":
					if (arity == 1) return null;
					break;
				case "ensureCanWrite":
					return this.index((BlockPos) args[0]) >= 0;
				case "isClientSide":
					return false;
				case "setCurrentlyGenerating", "blockUpdated", "updateNeighborsAt", "scheduleTick", "gameEvent", "neighborShapeChanged",
					"markPosForPostprocessing", "levelEvent", "playSound", "addParticle":
					return null;
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				case "toString":
					return "Seed LOD terrain";
				default:
			}
			if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
			throw new UnsupportedOperationException("Seed LOD's terrain doesn't do " + method.getName());
		}
	}
}
