package dev.winter.seedlod.gen;

import java.util.Arrays;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Canopy for far tiles, where growing every tree would cost too much ({@link RealTrees} does the near ones). Trees
 * sit at hashed spots in every chunk, as many as the biome grows on average, and a voxel column turns to canopy if
 * any crown reaches into it, as it would in Voxy's own levels built from real chunks.
 */
final class TreePlanter {
	/** Widest crown we expect; trees this close outside the tile can still reach into it. */
	private static final int MARGIN = 8;

	private final BiomeTrees trees;
	private final long seed;
	private final float[] cover = new float[Tile.SIZE * Tile.SIZE];
	private final int[] crownBottom = new int[Tile.SIZE * Tile.SIZE];
	private final int[] crownTop = new int[Tile.SIZE * Tile.SIZE];
	private final float[] strongest = new float[Tile.SIZE * Tile.SIZE];
	private final BlockState[] leaves = new BlockState[Tile.SIZE * Tile.SIZE];
	private final int[] ground = new int[Tile.SIZE * Tile.SIZE];

	TreePlanter(BiomeTrees trees, long seed) {
		this.trees = trees;
		this.seed = seed;
	}

	void plant(Tile tile, int originX, int originZ, int step) {
		Arrays.fill(this.cover, 0);
		Arrays.fill(this.strongest, 0);
		this.findGround(tile);

		int span = Tile.SIZE * step;
		for (int cz = Math.floorDiv(originZ - MARGIN, 16); cz <= Math.floorDiv(originZ + span + MARGIN - 1, 16); cz++)
			for (int cx = Math.floorDiv(originX - MARGIN, 16); cx <= Math.floorDiv(originX + span + MARGIN - 1, 16); cx++)
				this.plantChunk(tile, cx, cz, originX, originZ, step);

		for (int z = 0; z < Tile.SIZE; z++)
			for (int x = 0; x < Tile.SIZE; x++) {
				int column = x + z * Tile.SIZE;
				if (this.cover[column] <= 0) continue;
				int bottom = Math.floorDiv(this.crownBottom[column], step) - tile.voxelMinY;
				int top = Math.floorDiv(this.crownTop[column] - 1, step) - tile.voxelMinY;
				bottom = Math.max(bottom, this.ground[column] + 1);
				top = Math.max(top, bottom);
				for (int y = bottom; y <= top && y < tile.voxelsY; y++) this.put(tile, tile.index(x, y, z), this.leaves[column]);
			}
	}

	private void put(Tile tile, int index, BlockState state) {
		if (tile.states[index] != null) return;
		tile.states[index] = state;
		tile.light[index] = 15;
	}

	/** Top solid voxel of each column that trees could stand on, or -1. */
	private void findGround(Tile tile) {
		for (int z = 0; z < Tile.SIZE; z++)
			for (int x = 0; x < Tile.SIZE; x++) {
				int y = tile.voxelsY - 1;
				while (y >= 0 && tile.states[tile.index(x, y, z)] == null) y--;
				BlockState top = y < 0 ? null : tile.states[tile.index(x, y, z)];
				this.ground[x + z * Tile.SIZE] = top != null && canHoldTree(top) ? y : -1;
			}
	}

	/**
	 * Mod biomes cover their ground in all sorts of soil, so rather than list what trees grow on, this rules out what
	 * they never do: water, ice, bare rock, gravel and sand. The biome already says whether there are trees at all.
	 */
	private static boolean canHoldTree(BlockState top) {
		return top.getFluidState().isEmpty() && !top.is(BlockTags.ICE) && !top.is(BlockTags.BASE_STONE_OVERWORLD)
			&& !top.is(BlockTags.SAND) && !top.is(Blocks.GRAVEL);
	}

	private void plantChunk(Tile tile, int cx, int cz, int originX, int originZ, int step) {
		int centre = column((cx << 4) + 8, originX, step) + column((cz << 4) + 8, originZ, step) * Tile.SIZE;
		BiomeTrees.Mix mix = this.trees.of(tile.biomes[centre]);
		if (mix.perChunk() <= 0) return;
		long chunkHash = mix(this.seed, cx, cz, 1);
		int count = (int) mix.perChunk() + (unit(chunkHash) < mix.perChunk() % 1 ? 1 : 0);
		for (int i = 0; i < count; i++) {
			long h = mix(chunkHash, i, 0, 2);
			int wx = (cx << 4) + (int) (h & 15), wz = (cz << 4) + (int) ((h >> 4) & 15);
			BiomeTrees.Kind kind = pick(mix, unit(h >>> 8));
			int column = column(wx, originX, step) + column(wz, originZ, step) * Tile.SIZE;
			if (this.ground[column] < 0) continue;
			int groundY = (tile.voxelMinY + this.ground[column] + 1) * step;
			this.addCrown(kind, wx, wz, groundY, originX, originZ, step);
		}
	}

	/**
	 * Marks every voxel column the crown reaches into. Voxy builds its coarse levels from real chunks by letting any
	 * solid block win a voxel, so a crown that touches a voxel shows in it; doing the same keeps a forest as dense
	 * at every level as it would be from real chunks, instead of thinning out with distance.
	 */
	private void addCrown(BiomeTrees.Kind kind, int wx, int wz, int groundY, int originX, int originZ, int step) {
		int bottom = groundY + Math.round(kind.height() - kind.crownHeight());
		int top = groundY + Math.round(kind.height()) + 1;
		float r = kind.crownRadius(), cx = wx + 0.5F, cz = wz + 0.5F;
		for (int z = column((int) Math.floor(cz - r), originZ, step); z <= column((int) Math.ceil(cz + r), originZ, step); z++)
			for (int x = column((int) Math.floor(cx - r), originX, step); x <= column((int) Math.ceil(cx + r), originX, step); x++) {
				// Distance from the trunk to the nearest point of this column's square.
				float nx = Math.clamp(cx, originX + x * step, originX + (x + 1) * step), nz = Math.clamp(cz, originZ + z * step, originZ + (z + 1) * step);
				float dx = nx - cx, dz = nz - cz;
				if (dx * dx + dz * dz <= r * r) this.addCover(x + z * Tile.SIZE, 1.0F, kind, bottom, top);
			}
	}


	private void addCover(int column, float amount, BiomeTrees.Kind kind, int bottom, int top) {
		if (this.cover[column] == 0) {
			this.crownBottom[column] = bottom;
			this.crownTop[column] = top;
		} else {
			this.crownBottom[column] = Math.min(this.crownBottom[column], bottom);
			this.crownTop[column] = Math.max(this.crownTop[column], top);
		}
		this.cover[column] += amount;
		if (amount > this.strongest[column]) {
			this.strongest[column] = amount;
			this.leaves[column] = kind.leaves();
		}
	}

	private static BiomeTrees.Kind pick(BiomeTrees.Mix mix, float roll) {
		float target = roll * mix.perChunk();
		for (BiomeTrees.Kind kind : mix.kinds()) {
			target -= kind.perChunk();
			if (target <= 0) return kind;
		}
		return mix.kinds().getLast();
	}

	/** Column index within the tile, clamped to its edge. */
	private static int column(int block, int origin, int step) {
		return Math.clamp(Math.floorDiv(block - origin, step), 0, Tile.SIZE - 1);
	}

	private static long mix(long seed, long a, long b, long salt) {
		long h = seed ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
		h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
		h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
		return h ^ (h >>> 33);
	}

	private static float unit(long hash) {
		return (hash >>> 40) / (float) (1L << 24);
	}
}
