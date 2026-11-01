package dev.winter.seedlod.api;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A square of generated terrain, the full height of the dimension, at one level of detail: each voxel is a cube of
 * {@link #step()} blocks. Everything is in block coordinates; a call addresses the voxel holding that block. Walk it
 * one voxel at a time and sample your own functions at voxel centres:
 *
 * <pre>{@code
 * int step = area.step(), half = step / 2;
 * for (int z = area.minZ(); z < area.maxZ(); z += step)
 *     for (int x = area.minX(); x < area.maxX(); x += step) {
 *         int ground = area.height(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
 *         ...
 *     }
 * }</pre>
 */
public interface TerrainArea {
	WorldgenContext world();

	/** Level of detail, 0 (one block per voxel) to 4 (16 blocks). */
	int level();

	/** Blocks per voxel edge, {@code 1 << level()}. */
	int step();

	/** First block covered, inclusive. A multiple of {@link #step()}. */
	int minX();

	int minZ();

	/** Last block covered, exclusive. */
	int maxX();

	int maxZ();

	/** Lowest block covered. Can be below {@link WorldgenContext#minY()} when a voxel straddles the bottom. */
	int minY();

	/** Exclusive. */
	int maxY();

	/** Air outside the area. */
	BlockState getBlock(int x, int y, int z);

	/** Fills the voxel holding this block; air clears it. Outside the area it does nothing. */
	void setBlock(int x, int y, int z, BlockState state);

	/**
	 * Like a chunk's heightmap: the bottom of the voxel above the highest one {@code type} counts, or
	 * {@link #minY()} if none does. Reflects edits made so far.
	 */
	int height(Heightmap.Types type, int x, int z);

	/** The biome at the surface of this column; Seed LOD gives a whole column its surface biome. */
	Holder<Biome> surfaceBiome(int x, int z);
}
