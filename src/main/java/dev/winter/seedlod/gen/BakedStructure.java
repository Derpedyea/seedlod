package dev.winter.seedlod.gen;

import java.util.BitSet;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, pre-baked structure blocks with a 5-level mip pyramid (levels 0..4) ready to stamp into any {@link Tile}
 * with zero allocations and zero structure-placement overhead.
 *
 * <p>Each voxel entry is packed into a single {@code long}:
 * {@code rx (10b) | rz (10b) << 10 | ry (10b) << 20 | paletteIndex (12b) << 30 | blockLight (4b) << 42},
 * where {@code (rx, ry, rz)} are voxel coordinates relative to {@code (minX >> lvl, minY >> lvl, minZ >> lvl)}.
 */
final class BakedStructure {
	static final int LEVELS = 5;
	/** Coarsest-only cache entries keep levels {@code COARSE_LEVEL..4} (~1-3 KB each) and drop levels 0..2. */
	static final int COARSE_LEVEL = 3;
	static final int MAX_REL = 1023;
	static final int MAX_PALETTE = 4095;
	private static final int MAX_BEARD_DEPTH = 20;

	static final BakedStructure EMPTY = new BakedStructure(0, 0, 0, -1, -1, -1, new BlockState[0], new long[LEVELS][], null, 0);

	final int minX, minY, minZ;
	final int maxX, maxY, maxZ;
	/** {@code palette[0]} is null (air carve, only emitted at level 0); {@code palette[1..n]} are placed states. */
	final @Nullable BlockState[] palette;
	/** Per-level packed voxel arrays; entries below {@link #minLevel} are null. */
	final long @Nullable [][] mips;
	/** Rigid-piece foundation footprints for BEARD_THIN/BEARD_BOX structures: 5 ints each (minX, minZ, maxX, maxZ, floorY). */
	final int @Nullable [] beards;
	/** Finest level stored in {@link #mips} (0 for full-detail, {@link #COARSE_LEVEL} for coarse-only). */
	final int minLevel;

	BakedStructure(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, @Nullable BlockState[] palette,
		long @Nullable [][] mips, int @Nullable [] beards, int minLevel) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
		this.palette = palette;
		this.mips = mips;
		this.beards = beards;
		this.minLevel = minLevel;
	}

	static long pack(int rx, int ry, int rz, int paletteIdx, int blockLight) {
		return (rx & 0x3FFL) | ((rz & 0x3FFL) << 10) | ((ry & 0x3FFL) << 20)
			| ((paletteIdx & 0xFFFL) << 30) | ((blockLight & 0xFL) << 42);
	}

	boolean hasLevel(int lvl) {
		return this == EMPTY || lvl >= this.minLevel;
	}

	boolean intersects(int areaMinX, int areaMinZ, int areaMaxX, int areaMaxZ) {
		return this != EMPTY && this.maxX >= areaMinX && this.minX <= areaMaxX && this.maxZ >= areaMinZ && this.minZ <= areaMaxZ;
	}

	/** Drops fine levels (0..2) so distant structures stay cached in a couple of kilobytes. */
	BakedStructure downgradeToCoarse() {
		if (this == EMPTY || this.minLevel >= COARSE_LEVEL) return this;
		long[][] coarse = new long[LEVELS][];
		for (int l = COARSE_LEVEL; l < LEVELS; l++) coarse[l] = this.mips[l];
		return new BakedStructure(this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ, this.palette, coarse, this.beards, COARSE_LEVEL);
	}

	/** Stamps this structure (and any beard foundations) into {@code tile}, marking touched columns in {@code touched}. */
	void stamp(Tile tile, BitSet touched) {
		int lvl = tile.lvl;
		if (this.beards != null) this.stampBeards(tile, touched);
		long[] voxels = this.mips[lvl];
		if (voxels == null || voxels.length == 0) return;

		int size = tile.size;
		int voxelsY = tile.voxelsY;
		int ox = (this.minX >> lvl) - tile.voxelX;
		int oy = (this.minY >> lvl) - tile.voxelMinY;
		int oz = (this.minZ >> lvl) - tile.voxelZ;
		@Nullable BlockState[] states = tile.states;
		byte[] light = tile.light;
		@Nullable BlockState[] palette = this.palette;

		for (long v : voxels) {
			int vx = ox + (int) (v & 0x3FFL);
			if (vx < 0 || vx >= size) continue;
			int vz = oz + (int) ((v >>> 10) & 0x3FFL);
			if (vz < 0 || vz >= size) continue;
			int vy = oy + (int) ((v >>> 20) & 0x3FFL);
			if (vy < 0 || vy >= voxelsY) continue;

			int column = vx + vz * size;
			int idx = vy + column * voxelsY;
			BlockState state = palette[(int) ((v >>> 30) & 0xFFFL)];
			if (state == null) {
				if (states[idx] != null) {
					states[idx] = null;
					light[idx] = 15;
					touched.set(column);
				}
			} else {
				states[idx] = state;
				int bl = (int) ((v >>> 42) & 0xFL);
				light[idx] = (byte) (15 | (bl << 4));
				touched.set(column);
			}
		}
	}

	/**
	 * Fills terrain mounds beneath rigid {@code BEARD_THIN}/{@code BEARD_BOX} pieces so hillside buildings sit on
	 * biome-matched ground rather than floating in midair.
	 */
	private void stampBeards(Tile tile, BitSet touched) {
		int[] beards = this.beards;
		if (beards == null) return;
		int lvl = tile.lvl;
		int size = tile.size;
		int voxelsY = tile.voxelsY;
		int maxDepth = Math.max(1, MAX_BEARD_DEPTH >> lvl);
		@Nullable BlockState[] states = tile.states;
		byte[] light = tile.light;

		for (int i = 0; i < beards.length; i += 5) {
			int vx0 = Math.max(0, (beards[i] >> lvl) - tile.voxelX);
			int vz0 = Math.max(0, (beards[i + 1] >> lvl) - tile.voxelZ);
			int vx1 = Math.min(size - 1, (beards[i + 2] >> lvl) - tile.voxelX);
			int vz1 = Math.min(size - 1, (beards[i + 3] >> lvl) - tile.voxelZ);
			if (vx0 > vx1 || vz0 > vz1) continue;

			int floorVy = ((beards[i + 4] - 1) >> lvl) - tile.voxelMinY;
			if (floorVy < 0 || floorVy >= voxelsY) continue;

			for (int vz = vz0; vz <= vz1; vz++) {
				for (int vx = vx0; vx <= vx1; vx++) {
					int column = vx + vz * size;
					int base = column * voxelsY;
					BlockState atFloor = states[base + floorVy];
					if (atFloor != null && atFloor.getFluidState().isEmpty()) continue;

					int minScan = Math.max(0, floorVy - maxDepth);
					int groundVy = -1;
					for (int y = floorVy - 1; y >= minScan; y--) {
						BlockState s = states[base + y];
						if (s != null && s.getFluidState().isEmpty()) {
							groundVy = y;
							break;
						}
					}
					if (groundVy < 0) continue;

					BlockState top = states[base + groundVy];
					BlockState sub = subsurface(top);
					for (int y = groundVy + 1; y <= floorVy; y++) {
						states[base + y] = y == floorVy ? top : sub;
						light[base + y] = 0;
					}
					touched.set(column);
				}
			}
		}
	}

	private static BlockState subsurface(BlockState surface) {
		if (surface.is(Blocks.GRASS_BLOCK) || surface.is(Blocks.PODZOL) || surface.is(Blocks.MYCELIUM)) return Blocks.DIRT.defaultBlockState();
		if (surface.is(Blocks.SAND)) return Blocks.SANDSTONE.defaultBlockState();
		if (surface.is(Blocks.RED_SAND)) return Blocks.RED_SANDSTONE.defaultBlockState();
		return surface;
	}
}
