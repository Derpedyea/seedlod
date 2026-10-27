package dev.winter.seedlod.gen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBufferPool;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import org.jspecify.annotations.Nullable;

/**
 * Turns a seed into LOD voxels by sampling the dimension's final density on a grid of {@code 1 << lvl} blocks and
 * running its material rules on the surface. One per thread: it owns sampler caches and a compiled rule.
 */
public final class TileGen implements AutoCloseable {
	/**
	 * Rules only decide the blocks this close to a surface; deeper solid voxels are the default block. Vanilla's
	 * soil layers reach about this deep, and anything below is out of sight from afar.
	 */
	private static final int RULE_DEPTH_BLOCKS = 9;
	private static final int SCOUT_BLOCKS = 16;
	private static final int EXACT_MAX_LEVEL = 1;

	private final WorldgenView view;
	private final DensityBufferPool bufferPool;
	/** Up to {@link #EXACT_MAX_LEVEL}: vanilla's own density, interpolated between cell corners exactly as chunks are. */
	private final DensitySampler.Bound exactDensity;
	/** Coarser: interpolation stripped, sampled straight at the voxel stride. */
	private final DensitySampler.Bound strideDensity;
	private final BiomeResolver biomes;
	private final MaterialRuleContext ruleContext;
	private final RuleEvaluator rule;
	private final BiomeCache biomeCache = new BiomeCache();
	private final TreePlanter planter;
	private final RealTrees realTrees;
	private boolean plantTrees = true;
	/** Vanilla's interpolation cell in blocks (4x8x4 normally), or 0 if the density isn't interpolated. */
	private final int cellXz, cellY;
	/** Per-tile scratch, kept: a level 0 group's is megabytes, too big to allocate for every tile. */
	private boolean[] solid = new boolean[0];
	private int[] top = new int[0];

	public TileGen(WorldgenView view) {
		this.view = view;
		this.bufferPool = view.randomState.acquireDensityBufferPool();
		SamplerContext context = SamplerContext.builder().useBufferArena(this.bufferPool).enableCaches().build();
		DensitySamplerSet samplers = view.randomState.samplersWithContext(context);
		this.exactDensity = samplers.get(view.settings.noiseRouter().finalDensity());
		this.strideDensity = samplers.get(stripInterpolation(view.settings.noiseRouter().finalDensity()));
		int[] cell = interpolationCell(view.settings.noiseRouter().finalDensity());
		this.cellXz = cell[0];
		this.cellY = cell[1];
		this.biomes = view.biomes.createResolver(view.randomState.createClimateSampler(context));
		// A single-block expected volume keeps rules that prefill a whole chunk (ore veins) from sampling anything up
		// front; they fall back to sampling the few positions we actually ask about.
		this.ruleContext = new MaterialRuleContext(
			view.randomState.surfaceSystem(), view.randomState, new DensityVolume(1, 1, 1, 0, 0, 0), samplers,
			pos -> this.biomeCache.get(pos), view.genContext, null
		);
		this.rule = view.settings.materialRule().value().compile(this.ruleContext);
		this.planter = new TreePlanter(view.trees, view.randomState.seed());
		this.realTrees = new RealTrees(view);
	}

	/** Terrain only; trees come from data a server may not share identically with its clients. */
	TileGen withoutTrees() {
		this.plantTrees = false;
		return this;
	}

	/**
	 * Vanilla wraps terrain in {@code interpolated}, which only samples cheaply at its own cell size; at any other
	 * stride it samples every block and picks out the ones asked for. Sampling the input directly at our stride is
	 * what a LOD wants anyway.
	 */
	static DensityFunction stripInterpolation(DensityFunction function) {
		DfRewriteRule rule = new DfRewriteRule() {
			@Override
			public DensityFunction rewrite(DensityFunction f) {
				if (f instanceof DensityFunctions.HolderHolder(var holder)) f = holder.value();
				if (f instanceof InterpolatedFunction interpolated) return this.rewrite(interpolated.input());
				return f.rewriteChildren(this);
			}
		};
		return rule.rewrite(function);
	}

	/** The cell of the first {@code interpolated} in the function, as {xz, y} blocks; {0, 0} if there's none. */
	private static int[] interpolationCell(DensityFunction function) {
		int[] cell = new int[2];
		new DfRewriteRule() {
			@Override
			public DensityFunction rewrite(DensityFunction f) {
				if (f instanceof DensityFunctions.HolderHolder(var holder)) f = holder.value();
				if (f instanceof InterpolatedFunction interpolated && cell[0] == 0) {
					cell[0] = interpolated.cellSizeXz();
					cell[1] = interpolated.cellSizeY();
				}
				return f.rewriteChildren(this);
			}
		}.rewrite(function);
		return cell;
	}

	public Tile generate(int lvl, int tileX, int tileZ) {
		Tile tile = this.terrain(lvl, tileX * Tile.SIZE, tileZ * Tile.SIZE, Tile.SIZE);
		if (this.plantTrees) this.planter.plant(tile, tile.voxelX << lvl, tile.voxelZ << lvl, 1 << lvl);
		return tile;
	}

	/**
	 * The 2x2 tiles of a group (they're written together). Up to {@link RealTrees#MAX_LEVEL} the group is generated
	 * with a margin wide enough for trees standing just outside to reach in, and grows vanilla's own trees.
	 */
	public Tile[] generateGroup(int lvl, int groupX, int groupZ) {
		Tile[] group = new Tile[4];
		if (lvl > RealTrees.MAX_LEVEL || !this.plantTrees) {
			for (int i = 0; i < 4; i++) group[i] = this.generate(lvl, groupX * 2 + (i & 1), groupZ * 2 + (i >> 1));
			return group;
		}
		int margin = RealTrees.MARGIN >> lvl;
		Tile area = this.terrain(lvl, groupX * 2 * Tile.SIZE - margin, groupZ * 2 * Tile.SIZE - margin, 2 * Tile.SIZE + 2 * margin);
		this.realTrees.plant(area, this.biomes);
		for (int i = 0; i < 4; i++) group[i] = area.cut(groupX * 2 + (i & 1), groupZ * 2 + (i >> 1));
		return group;
	}

	private Tile terrain(int lvl, int voxelX, int voxelZ, int size) {
		int step = 1 << lvl;
		int half = step >> 1;
		int originX = voxelX * step;
		int originZ = voxelZ * step;
		int voxelMinY = Math.floorDiv(this.view.minY, step);
		int voxelMaxY = Math.ceilDiv(this.view.minY + this.view.height, step);
		int voxelsY = voxelMaxY - voxelMinY;
		Tile tile = new Tile(lvl, voxelX, voxelZ, size, voxelMinY, voxelsY);
		this.biomeCache.clear();

		if (this.solid.length < size * size * voxelsY) this.solid = new boolean[size * size * voxelsY];
		boolean[] solid = this.solid;
		Arrays.fill(solid, 0, size * size * voxelsY, false);
		this.sampleSolid(tile, solid, originX, originZ, step);

		// Height of the top solid voxel per column, in blocks; feeds the rules' slope checks.
		if (this.top.length < size * size) this.top = new int[size * size];
		int[] top = this.top;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				int y = voxelsY - 1;
				while (y >= 0 && !solid[tile.index(x, y, z)]) y--;
				top[x + z * size] = y < 0 ? this.view.minY : (voxelMinY + y) * step + step - 1;
			}
		}

		for (int z = 0; z < size; z++) {
			int blockZ = originZ + z * step + half;
			for (int x = 0; x < size; x++) {
				int blockX = originX + x * step + half;
				int column = x + z * size;
				int surfaceY = Math.max(top[column], this.view.seaLevel - 1);
				Holder<Biome> biome = this.biomeCache.surface(blockX, surfaceY, blockZ);
				tile.biomes[column] = biome;

				int gradientX = (top[Math.min(x + 1, size - 1) + z * size] - top[Math.max(x - 1, 0) + z * size]) / step;
				int gradientZ = (top[x + Math.min(z + 1, size - 1) * size] - top[x + Math.max(z - 1, 0) * size]) / step;
				this.ruleContext.updateXZ(blockX, blockZ, gradientX, gradientZ);
				this.fillColumn(tile, solid, x, z, blockX, blockZ, step);
			}
		}
		return tile;
	}

	/**
	 * Most of a column is sky or deep rock. A sparse pass, every {@link #SCOUT_BLOCKS} blocks each way, finds the band
	 * holding the tile's surface; only that band is sampled at full resolution, below it is solid, above it air. A
	 * column whose ground turns out to sit below the band, or to rise through its top, is followed down or up (see
	 * {@link #extendBand}), so the scout's spacing costs nothing but time. Anything floating clear of the band (a
	 * thin sliver of overhang) can be missed, which a LOD can live with.
	 */
	private void sampleSolid(Tile tile, boolean[] solid, int originX, int originZ, int step) {
		// Interpolation only evaluates the noise at cell corners (every 4x8x4 blocks) and blends the rest, so at fine
		// strides it's both what chunks look like and far cheaper than evaluating every voxel. At coarse strides the
		// blend would be done for every block underneath, so there the noise is sampled at the voxels directly.
		DensitySampler.Bound density = tile.lvl <= EXACT_MAX_LEVEL ? this.exactDensity : this.strideDensity;
		int half = step >> 1;
		int voxelMinY = tile.voxelMinY, voxelsY = tile.voxelsY;
		int bandLo = 0, bandHi = voxelsY - 1;
		int scoutEvery = SCOUT_BLOCKS / step;
		if (tile.lvl <= EXACT_MAX_LEVEL && this.cellXz > 0) {
			int[] band = this.scoutCells(tile, originX, originZ, step);
			if (band == null) return;
			bandLo = band[0];
			bandHi = band[1];
			for (int column = 0; column < tile.size * tile.size; column++)
				for (int y = 0; y < bandLo; y++) solid[y + column * voxelsY] = true;
		} else if (scoutEvery >= 2) {
			int scouts = Math.ceilDiv(voxelsY, scoutEvery);
			// Sideways too: one scout column per scoutEvery voxels (the last may land past the tile's edge).
			int across = Math.ceilDiv(tile.size - 1, scoutEvery) + 1;
			int spacing = scoutEvery * step;
			DensityVolume scout = new DensityVolume(
				across, scouts, across, originX + half, voxelMinY * step + half, originZ + half, spacing, SCOUT_BLOCKS, spacing
			);
			// The band spans every column's topmost solid scout. Caves under that are left solid: nobody sees them.
			int lowestTop = scouts, highestSolid = -1;
			try (ScopedDensityBuffer buffer = density.sampleVolume(scout)) {
				for (int column = 0; column < across * across; column++) {
					int y = scouts - 1;
					while (y >= 0 && buffer.get(y + column * scouts) <= 0.0F) y--;
					if (y < 0) continue;
					lowestTop = Math.min(lowestTop, y);
					highestSolid = Math.max(highestSolid, y);
				}
			}
			if (highestSolid < 0) return;
			// One scout step of slack either side: the surface can sit anywhere between two scouts.
			bandLo = Math.clamp((long) (lowestTop - 1) * scoutEvery, 0, voxelsY - 1);
			bandHi = Math.clamp((long) (highestSolid + 2) * scoutEvery - 1, bandLo, voxelsY - 1);
			for (int column = 0; column < tile.size * tile.size; column++)
				for (int y = 0; y < bandLo; y++) solid[y + column * voxelsY] = true;
		}
		if (this.cellXz == step && this.cellY > step && this.cellY % step == 0) {
			this.sampleBandFromCorners(tile, solid, originX, originZ, step, bandLo, bandHi);
			this.extendBand(tile, solid, this.exactDensity, originX, originZ, step, bandLo, bandHi);
			return;
		}
		int bandSize = bandHi - bandLo + 1;
		DensityVolume volume = new DensityVolume(
			tile.size, bandSize, tile.size, originX + half, (voxelMinY + bandLo) * step + half, originZ + half, step, step, step
		);
		try (ScopedDensityBuffer buffer = density.sampleVolume(volume)) {
			for (int column = 0; column < tile.size * tile.size; column++)
				for (int y = 0; y < bandSize; y++) solid[bandLo + y + column * voxelsY] = buffer.get(y + column * bandSize) > 0.0F;
		}
		this.extendBand(tile, solid, density, originX, originZ, step, bandLo, bandHi);
	}

	/**
	 * The band where a voxel is exactly one interpolation cell wide (lvl 2 with vanilla's 4x8x4): every voxel centre
	 * sits midway between four corner columns, so the cell corners and a blend give what a chunk would have there, for
	 * half the samples of evaluating each voxel.
	 */
	private void sampleBandFromCorners(Tile tile, boolean[] solid, int originX, int originZ, int step, int bandLo, int bandHi) {
		int half = step >> 1;
		int voxelsY = tile.voxelsY, size = tile.size;
		int firstY = (tile.voxelMinY + bandLo) * step + half, lastY = (tile.voxelMinY + bandHi) * step + half;
		int y0 = Math.floorDiv(firstY, this.cellY) * this.cellY;
		int rows = Math.floorDiv(lastY - y0, this.cellY) + 2;
		// Voxel x is cell x: corners at the voxel's two edges, originX + x * step and one step on.
		DensityVolume corners = new DensityVolume(size + 1, rows, size + 1, originX, y0, originZ, step, this.cellY, step);
		try (ScopedDensityBuffer buffer = this.exactDensity.sampleVolume(corners)) {
			for (int z = 0; z < size; z++)
				for (int x = 0; x < size; x++) {
					int base = (x + z * size) * voxelsY;
					for (int y = bandLo; y <= bandHi; y++) {
						int blockY = (tile.voxelMinY + y) * step + half;
						int cy = Math.floorDiv(blockY - y0, this.cellY);
						float t = (float) (blockY - y0 - cy * this.cellY) / this.cellY;
						float below = buffer.get(corners.indexUnchecked(x, cy, z)) + buffer.get(corners.indexUnchecked(x + 1, cy, z))
							+ buffer.get(corners.indexUnchecked(x, cy, z + 1)) + buffer.get(corners.indexUnchecked(x + 1, cy, z + 1));
						float above = buffer.get(corners.indexUnchecked(x, cy + 1, z)) + buffer.get(corners.indexUnchecked(x + 1, cy + 1, z))
							+ buffer.get(corners.indexUnchecked(x, cy + 1, z + 1)) + buffer.get(corners.indexUnchecked(x + 1, cy + 1, z + 1));
						solid[base + y] = below + (above - below) * t > 0.0F;
					}
				}
		}
	}

	/**
	 * Follows the columns the band cut short. No ground anywhere in the band means it lies lower (the scout stepped
	 * over a valley): sample down until it's found. (Air at the band's bottom under ground that was found is a cave,
	 * left solid like every cave under the band.) Solid at its top means terrain may carry on up: sample the rest of
	 * the column.
	 */
	private void extendBand(Tile tile, boolean[] solid, DensitySampler.Bound density, int originX, int originZ, int step, int bandLo, int bandHi) {
		int half = step >> 1;
		int voxelsY = tile.voxelsY;
		int chunk = Math.max(2, SCOUT_BLOCKS / step);
		for (int column = 0; column < tile.size * tile.size; column++) {
			int base = column * voxelsY;
			int blockX = originX + (column % tile.size) * step + half, blockZ = originZ + (column / tile.size) * step + half;
			if (bandLo > 0 && !anySolid(solid, base + bandLo, base + bandHi)) {
				for (int top = bandLo - 1; top >= 0; ) {
					int from = Math.max(0, top - chunk + 1);
					boolean found = this.sampleColumn(tile, solid, density, blockX, blockZ, step, base, from, top);
					if (found) break;
					top = from - 1;
				}
			}
			if (bandHi < voxelsY - 1 && solid[base + bandHi]) this.sampleColumn(tile, solid, density, blockX, blockZ, step, base, bandHi + 1, voxelsY - 1);
		}
	}

	private static boolean anySolid(boolean[] solid, int from, int to) {
		for (int i = to; i >= from; i--) if (solid[i]) return true;
		return false;
	}

	/** Samples voxels {@code from..to} of one column into {@code solid}; true if any is solid. */
	private boolean sampleColumn(Tile tile, boolean[] solid, DensitySampler.Bound density, int blockX, int blockZ, int step, int base, int from, int to) {
		int count = to - from + 1;
		DensityVolume volume = new DensityVolume(1, count, 1, blockX, (tile.voxelMinY + from) * step + (step >> 1), blockZ, step, step, step);
		boolean any = false;
		try (ScopedDensityBuffer buffer = density.sampleVolume(volume)) {
			for (int y = 0; y < count; y++) {
				boolean value = buffer.get(volume.indexUnchecked(0, y, 0)) > 0.0F;
				solid[base + from + y] = value;
				any |= value;
			}
		}
		return any;
	}

	/**
	 * The scout for interpolated density: its cell corners, the only points it really evaluates. Asking for any other
	 * stride makes it interpolate every block of the tile first. Interpolation can't rise above its corners, so the
	 * topmost solid corner row bounds the surface to within a cell. Returns the band in voxels, or null if all air.
	 */
	private int @Nullable [] scoutCells(Tile tile, int originX, int originZ, int step) {
		int half = step >> 1;
		int minX = originX + half, maxX = originX + (tile.size - 1) * step + half;
		int minZ = originZ + half, maxZ = originZ + (tile.size - 1) * step + half;
		int minY = tile.voxelMinY * step + half, maxY = (tile.voxelMinY + tile.voxelsY - 1) * step + half;
		int x0 = Math.floorDiv(minX, this.cellXz) * this.cellXz, z0 = Math.floorDiv(minZ, this.cellXz) * this.cellXz;
		int y0 = Math.floorDiv(minY, this.cellY) * this.cellY;
		int nx = Math.ceilDiv(maxX - x0, this.cellXz) + 1, nz = Math.ceilDiv(maxZ - z0, this.cellXz) + 1;
		int ny = Math.ceilDiv(maxY - y0, this.cellY) + 1;
		DensityVolume corners = new DensityVolume(nx, ny, nz, x0, y0, z0, this.cellXz, this.cellY, this.cellXz);
		int lowestTop = Integer.MAX_VALUE, highestSolid = Integer.MIN_VALUE;
		try (ScopedDensityBuffer buffer = this.exactDensity.sampleVolume(corners)) {
			for (int z = 0; z < nz; z++)
				for (int x = 0; x < nx; x++) {
					int y = ny - 1;
					while (y >= 0 && buffer.get(corners.indexUnchecked(x, y, z)) <= 0.0F) y--;
					if (y < 0) continue;
					lowestTop = Math.min(lowestTop, y);
					highestSolid = Math.max(highestSolid, y);
				}
		}
		if (highestSolid < 0) return null;
		// Slack as the plain scout has: a scout step below the lowest top, and above the highest solid corner's cell.
		int loBlock = y0 + lowestTop * this.cellY - SCOUT_BLOCKS;
		int hiBlock = y0 + highestSolid * this.cellY + this.cellY + SCOUT_BLOCKS;
		int bandLo = Math.clamp(Math.floorDiv(loBlock, step) - tile.voxelMinY, 0, tile.voxelsY - 1);
		int bandHi = Math.clamp(Math.floorDiv(hiBlock, step) - tile.voxelMinY, bandLo, tile.voxelsY - 1);
		return new int[]{bandLo, bandHi};
	}

	/** Mirrors vanilla's surface pass over one column, top down, in voxel steps. */
	private void fillColumn(Tile tile, boolean[] solid, int x, int z, int blockX, int blockZ, int step) {
		int half = step >> 1;
		int stoneAbove = 0;
		int waterHeight = Integer.MIN_VALUE;
		int ceilingY = Integer.MAX_VALUE;
		for (int y = tile.voxelsY - 1; y >= 0; y--) {
			int index = tile.index(x, y, z);
			int blockY = (tile.voxelMinY + y) * step + half;
			if (!solid[index]) {
				stoneAbove = 0;
				if (blockY < this.view.seaLevel) {
					if (waterHeight == Integer.MIN_VALUE) waterHeight = Math.min(this.view.seaLevel, (tile.voxelMinY + y + 1) * step);
					tile.states[index] = this.view.defaultFluid;
					// Light at the voxel's top, so the surface voxel is fully lit however coarse the level.
					tile.light[index] = (byte) Mth.clamp(15 - (waterHeight - (tile.voxelMinY + y + 1) * step), 0, 15);
				} else {
					waterHeight = Integer.MIN_VALUE;
					tile.light[index] = 15;
				}
				continue;
			}

			if (ceilingY >= y) {
				int below = y - 1;
				while (below >= 0 && solid[tile.index(x, below, z)]) below--;
				ceilingY = below + 1;
			}
			int depthAbove = stoneAbove == 0 ? 1 : 1 + stoneAbove * step;
			int depthBelow = (y - ceilingY) * step + 1;
			stoneAbove++;

			BlockState state = null;
			if (depthAbove <= RULE_DEPTH_BLOCKS || depthBelow <= RULE_DEPTH_BLOCKS) {
				this.ruleContext.updateY(depthAbove, depthBelow, waterHeight, blockY);
				state = this.rule.tryApply(blockX, blockY, blockZ);
			}
			tile.states[index] = state != null ? state : this.view.defaultBlock;
		}
	}

	@Override
	public void close() {
		this.view.randomState.releaseDensityBufferPool(this.bufferPool);
	}

	/**
	 * Rules ask for the biome per block. Far terrain only shows the surface, so every block of a column gets its
	 * surface biome, looked up once per quart column (4x4 blocks), as vanilla's biome storage is laid out.
	 */
	private final class BiomeCache {
		private final Long2ObjectOpenHashMap<Holder<Biome>> quarts = new Long2ObjectOpenHashMap<>();
		private Holder<Biome> column;

		void clear() {
			this.quarts.clear();
		}

		Holder<Biome> surface(int blockX, int surfaceY, int blockZ) {
			long key = (long) (blockX >> 2) << 32 | ((blockZ >> 2) & 0xFFFFFFFFL);
			Holder<Biome> biome = this.quarts.get(key);
			if (biome == null) {
				biome = TileGen.this.biomes.getNoiseBiome(blockX >> 2, surfaceY >> 2, blockZ >> 2);
				this.quarts.put(key, biome);
			}
			return this.column = biome;
		}

		Holder<Biome> get(BlockPos pos) {
			return this.column;
		}
	}
}
