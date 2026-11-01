package dev.winter.seedlod.gen;

import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * One generated column of LOD voxels: 32x32 columns of {@code 1 << lvl} blocks each, covering the dimension's full
 * height. Indexing matches {@link net.minecraft.world.level.levelgen.densityfunction.DensityVolume}: y fastest.
 * While being generated a tile can be wider ({@link #size} columns a side, starting at voxel {@link #voxelX},
 * {@link #voxelZ}), and is then cut into regular ones.
 */
public final class Tile {
	public static final int SIZE = 32;

	public final int lvl;
	public final int tileX;
	public final int tileZ;
	public final int size;
	/** Horizontal voxel coordinates of column (0, 0). */
	public final int voxelX;
	public final int voxelZ;
	/** Voxel y index (in voxel units, not blocks) of {@code states[.. y=0 ..]}. */
	public final int voxelMinY;
	public final int voxelsY;
	/** Null is air. */
	public final @Nullable BlockState[] states;
	/** Light per voxel, packed the way Voxy stores it: sky | block << 4. */
	public final byte[] light;
	/** Surface biome per column, x + z * 32. */
	public final Holder<Biome>[] biomes;
	/** Biome Voxy tints each column with: {@link #biomes} dithered across borders, so they blend from afar. */
	public final Holder<Biome>[] tints;

	Tile(int lvl, int tileX, int tileZ, int voxelMinY, int voxelsY) {
		this(lvl, tileX * SIZE, tileZ * SIZE, SIZE, voxelMinY, voxelsY);
	}

	@SuppressWarnings("unchecked")
	Tile(int lvl, int voxelX, int voxelZ, int size, int voxelMinY, int voxelsY) {
		this.lvl = lvl;
		this.tileX = Math.floorDiv(voxelX, SIZE);
		this.tileZ = Math.floorDiv(voxelZ, SIZE);
		this.size = size;
		this.voxelX = voxelX;
		this.voxelZ = voxelZ;
		this.voxelMinY = voxelMinY;
		this.voxelsY = voxelsY;
		this.states = new BlockState[size * size * voxelsY];
		this.light = new byte[size * size * voxelsY];
		this.biomes = new Holder[size * size];
		this.tints = new Holder[size * size];
	}

	public int index(int x, int y, int z) {
		return y + (x + z * this.size) * this.voxelsY;
	}

	/** Column index holding block x, z, or -1 outside the tile. */
	int column(int blockX, int blockZ) {
		int vx = (blockX >> this.lvl) - this.voxelX, vz = (blockZ >> this.lvl) - this.voxelZ;  // floorDiv by the step
		return vx < 0 || vz < 0 || vx >= this.size || vz >= this.size ? -1 : vx + vz * this.size;
	}

	/** Voxel index holding a block, or -1 outside the tile. */
	int blockIndex(int blockX, int blockY, int blockZ) {
		int column = this.column(blockX, blockZ);
		int vy = (blockY >> this.lvl) - this.voxelMinY;
		return column < 0 || vy < 0 || vy >= this.voxelsY ? -1 : vy + column * this.voxelsY;
	}

	/** The bottom of the voxel above the column's highest state that {@code counts}, or {@code floor} if none does. */
	int height(int column, Predicate<BlockState> counts, int floor) {
		int base = column * this.voxelsY;
		for (int y = this.voxelsY - 1; y >= 0; y--) {
			BlockState state = this.states[base + y];
			if (state != null && counts.test(state)) return (this.voxelMinY + y + 1) << this.lvl;
		}
		return floor;
	}

	/**
	 * Recomputes a column's light the way the terrain pass sets it: open sky in air, darker with depth in fluid.
	 * Solid voxels keep theirs.
	 */
	void relight(int column, int seaLevel) {
		int base = column * this.voxelsY, step = 1 << this.lvl;
		int fluidTop = Integer.MIN_VALUE;
		for (int y = this.voxelsY - 1; y >= 0; y--) {
			BlockState state = this.states[base + y];
			int top = (this.voxelMinY + y + 1) * step;
			if (state == null) {
				fluidTop = Integer.MIN_VALUE;
				this.light[base + y] = 15;
			} else if (!state.getFluidState().isEmpty()) {
				// The sea's surface is at sea level even where a coarse voxel reaches above it.
				if (fluidTop == Integer.MIN_VALUE) fluidTop = top - step + (step >> 1) < seaLevel ? Math.min(top, seaLevel) : top;
				this.light[base + y] = (byte) Math.clamp(15 - (fluidTop - top), 0, 15);
			}
		}
	}

	/** The regular tile (tileX, tileZ) out of this wider one. */
	Tile cut(int tileX, int tileZ) {
		Tile out = new Tile(this.lvl, tileX, tileZ, this.voxelMinY, this.voxelsY);
		int offsetX = tileX * SIZE - this.voxelX, offsetZ = tileZ * SIZE - this.voxelZ;
		for (int z = 0; z < SIZE; z++)
			for (int x = 0; x < SIZE; x++) {
				int from = this.index(offsetX + x, 0, offsetZ + z), to = out.index(x, 0, z);
				System.arraycopy(this.states, from, out.states, to, this.voxelsY);
				System.arraycopy(this.light, from, out.light, to, this.voxelsY);
				out.biomes[x + z * SIZE] = this.biomes[offsetX + x + (offsetZ + z) * this.size];
				out.tints[x + z * SIZE] = this.tints[offsetX + x + (offsetZ + z) * this.size];
			}
		return out;
	}
}
