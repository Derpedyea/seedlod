package dev.winter.seedlod.gen;

import dev.winter.seedlod.api.TerrainArea;
import dev.winter.seedlod.api.TerrainModifier;
import dev.winter.seedlod.api.WorldgenContext;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Runs the plugins' {@link TerrainModifier}s over a tile. One per {@link TileGen}, so per thread. */
final class ModifiedArea implements TerrainArea {
	private record Bound(Identifier id, TerrainModifier modifier) {
	}

	private final WorldgenView view;
	private final List<Bound> modifiers = new ArrayList<>();
	/** Columns a modifier set blocks in, to relight. */
	private final BitSet touched = new BitSet();
	private Tile tile;

	ModifiedArea(WorldgenView view) {
		this.view = view;
		for (Hooks.Modifier registered : Hooks.get().modifiers) {
			try {
				TerrainModifier modifier = registered.factory().create(view);
				if (modifier != null) this.modifiers.add(new Bound(registered.id(), modifier));
			} catch (RuntimeException e) {
				Hooks.report("Seed LOD terrain modifier " + registered.id() + " failed to start for " + view.dimension.identifier() + "; going on without it", e);
			}
		}
	}

	void modify(Tile tile) {
		if (this.modifiers.isEmpty()) return;
		this.tile = tile;
		this.touched.clear();
		for (Bound bound : this.modifiers) {
			try {
				bound.modifier().modify(this);
			} catch (RuntimeException e) {
				Hooks.report("Seed LOD terrain modifier " + bound.id() + " failed; skipping it where it does", e);
			}
		}
		for (int column = this.touched.nextSetBit(0); column >= 0; column = this.touched.nextSetBit(column + 1))
			tile.relight(column, this.view.seaLevel);
		this.tile = null;
	}

	@Override
	public WorldgenContext world() {
		return this.view;
	}

	@Override
	public int level() {
		return this.tile.lvl;
	}

	@Override
	public int step() {
		return 1 << this.tile.lvl;
	}

	@Override
	public int minX() {
		return this.tile.voxelX << this.tile.lvl;
	}

	@Override
	public int minZ() {
		return this.tile.voxelZ << this.tile.lvl;
	}

	@Override
	public int maxX() {
		return (this.tile.voxelX + this.tile.size) << this.tile.lvl;
	}

	@Override
	public int maxZ() {
		return (this.tile.voxelZ + this.tile.size) << this.tile.lvl;
	}

	@Override
	public int minY() {
		return this.tile.voxelMinY << this.tile.lvl;
	}

	@Override
	public int maxY() {
		return (this.tile.voxelMinY + this.tile.voxelsY) << this.tile.lvl;
	}

	@Override
	public BlockState getBlock(int x, int y, int z) {
		int index = this.tile.blockIndex(x, y, z);
		BlockState state = index < 0 ? null : this.tile.states[index];
		return state == null ? Blocks.AIR.defaultBlockState() : state;
	}

	@Override
	public void setBlock(int x, int y, int z, BlockState state) {
		int index = this.tile.blockIndex(x, y, z);
		if (index < 0) return;
		this.tile.states[index] = state.isAir() ? null : state;
		this.touched.set(index / this.tile.voxelsY);
	}

	@Override
	public int height(Heightmap.Types type, int x, int z) {
		int column = this.tile.column(x, z);
		return column < 0 ? this.minY() : this.tile.height(column, type.isOpaque(), this.minY());
	}

	@Override
	public Holder<Biome> surfaceBiome(int x, int z) {
		int column = this.tile.column(x, z);
		// Outside, the nearest column's: a modifier asking just past the edge gets a sensible answer.
		if (column < 0) {
			int vx = Math.clamp((x >> this.tile.lvl) - this.tile.voxelX, 0, this.tile.size - 1);
			int vz = Math.clamp((z >> this.tile.lvl) - this.tile.voxelZ, 0, this.tile.size - 1);
			column = vx + vz * this.tile.size;
		}
		return this.tile.biomes[column];
	}
}
