package dev.winter.seedlod.client;

import dev.winter.seedlod.gen.Tile;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/** Writes generated tiles into Voxy as coarse leaf sections, keeping {@link LodTree}'s rules. */
final class VoxyWriter {
	private final WorldEngine engine;
	private final Mapper mapper;
	private final LodTree.RealChunks real;
	private final Map<BlockState, Integer> blockIds = new IdentityHashMap<>();
	private final Map<Holder<Biome>, Integer> biomeIds = new IdentityHashMap<>();
	/** One section being built; a writer belongs to one worker thread. */
	private final long[] data = new long[LodTree.VOXELS];
	private final boolean[] ours = new boolean[32 * 32];

	VoxyWriter(WorldEngine engine) {
		this.engine = engine;
		this.mapper = engine.getMapper();
		this.real = LodTree.real(engine);
	}

	/** Writes a 2x2 group of tiles that share parents, so a parent never gains only some of its children. */
	void writeGroup(Tile[] group) {
		Tile first = group[0];
		int shift = WorldEngine.MAX_LOD_LAYER - first.lvl;
		if (shift == 0) {
			// At the top level each tile is its own column (and has no parent to keep consistent).
			for (Tile tile : group) this.writeLocked(tile.tileX, tile.tileZ, tile);
			return;
		}
		this.writeLocked(first.tileX >> shift, first.tileZ >> shift, group);
	}

	private void writeLocked(int lvl4X, int lvl4Z, Tile... tiles) {
		ReentrantLock lock = LodTree.lock(lvl4X, lvl4Z);
		lock.lock();
		try {
			for (Tile tile : tiles) this.writeTile(tile);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Sections of sky above the terrain are written too, as air with full skylight: Voxy lights a face from the voxel
	 * in front of it, even across into a neighbouring section, and a section nobody wrote reads as pitch black. A
	 * neighbouring tile's hill can rise this far above ours, so its sides face lit air.
	 */
	private static final int SKY_SECTIONS = 3;

	private void writeTile(Tile tile) {
		if (LodTree.allReal(this.real, tile.lvl, tile.tileX, tile.tileZ)) return;
		int minSection = Math.floorDiv(tile.voxelMinY, 32);
		int maxSection = Math.floorDiv(tile.voxelMinY + tile.voxelsY - 1, 32);
		long[] data = this.data;
		int highestSolid = Integer.MIN_VALUE;
		for (int sy = minSection; sy <= maxSection; sy++) {
			boolean blocks = this.build(tile, sy, data);
			if (blocks) highestSolid = sy;
			else if (highestSolid == Integer.MIN_VALUE || sy > highestSolid + SKY_SECTIONS) continue;
			this.place(tile.lvl, tile.tileX, sy, tile.tileZ, data);
		}
	}

	/** Fills {@code out} with one section of the tile. Returns false when it's all air (nothing to draw). */
	private boolean build(Tile tile, int sy, long[] out) {
		int baseY = sy * 32 - tile.voxelMinY;
		if (baseY >= tile.voxelsY) {
			Arrays.fill(out, LodTree.UNWRITTEN);
			return false;
		}
		boolean any = false;
		for (int z = 0; z < 32; z++)
			for (int x = 0; x < 32; x++) {
				int biome = this.biomeId(tile.tints[x + z * 32]);
				int colBase = (x + z * 32) * tile.voxelsY;
				for (int ly = 0; ly < 32; ly++) {
					int y = baseY + ly;
					long id;
					if (y < 0) {
						id = Mapper.airWithLight(0);
					} else if (y >= tile.voxelsY) {
						id = LodTree.UNWRITTEN;
					} else {
						int i = colBase + y;
						BlockState state = tile.states[i];
						if (state == null) {
							id = Mapper.airWithLight(tile.light[i]);
						} else {
							id = Mapper.composeMappingId(tile.light[i], this.blockId(state), biome);
							any = true;
						}
					}
					out[WorldSection.getIndex(x, ly, z)] = id;
				}
			}
		return any;
	}

	/**
	 * Puts one section of ours into the tree, writing only into columns that belong to us (see
	 * {@link LodTree#ourColumns}) so real chunks are never overwritten even where they are air. A section that already
	 * has children also gets upsampled children for missing octants that have blocks in our columns, so it can't show
	 * holes up close.
	 */
	private void place(int lvl, int x, int y, int z, long[] data) {
		if (lvl == 0) {
			this.placeLevel0(x, y, z, data);
			return;
		}
		WorldSection section = this.engine.acquire(lvl, x, y, z);
		try {
			long[] raw = section._unsafeGetRawDataArray();
			byte children = section.getNonEmptyChildren();
			boolean[] ours = LodTree.ourColumns(this.real, lvl, x, z, raw, this.ours);
			if (children == 0) {
				boolean changed = false;
				boolean hasOursBlocks = false;
				for (int i = 0; i < LodTree.VOXELS; i++) {
					if (!ours[i & 1023]) continue;
					long next = data[i];
					if (!Mapper.isAir(next)) hasOursBlocks = true;
					if (raw[i] != next) {
						raw[i] = next;
						changed = true;
					}
				}
				if (changed) this.engine.markDirty(section, WorldEngine.UPDATE_TYPE_BLOCK_BIT, 0b111111);
				// Sky only lights its neighbours; as a child it would just be an empty node to visit.
				if (hasOursBlocks) this.linkToParents(section);
				return;
			}
			boolean changed = false;
			for (int i = 0; i < LodTree.VOXELS; i++) {
				if (!ours[i & 1023]) continue;
				long next = data[i];
				if (raw[i] != next) {
					raw[i] = next;
					changed = true;
				}
			}
			if (changed) this.engine.markDirty(section, WorldEngine.UPDATE_TYPE_BLOCK_BIT, 0b111111);
			int missing = ~children & 0xFF;
			LodTree.fillOctants(this.engine, this.real, section, data, missing);
			if (lvl == 1) LodTree.patchLevel0Children(this.engine, this.real, section, data, children);
		} finally {
			section.release();
		}
	}

	/** Level 0 is where real chunks live; we replace what's ours and leave real terrain alone. */
	private void placeLevel0(int x, int y, int z, long[] data) {
		WorldSection section = this.engine.acquire(0, x, y, z);
		try {
			long[] raw = section._unsafeGetRawDataArray();
			boolean[] ours = LodTree.ourColumns(this.real, 0, x, z, raw, this.ours);
			int delta = 0;
			boolean changed = false;
			for (int i = 0; i < LodTree.VOXELS; i++) {
				long old = raw[i];
				if (!ours[i & 1023] || old == data[i]) continue;
				raw[i] = data[i];
				delta += Mapper.isNotAirInt(data[i]) - Mapper.isNotAirInt(old);
				changed = true;
			}
			if (!changed) return;
			section.addNonEmptyBlockCount(delta);
			boolean stateChanged = section.updateLvl0State();
			this.engine.markDirty(section, WorldEngine.UPDATE_TYPE_BLOCK_BIT | (stateChanged ? WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT : 0), 0b111111);
			if (section.getNonEmptyChildren() != 0) this.linkToParents(section);
		} finally {
			section.release();
		}
	}

	/** Marks a newly filled leaf in each ancestor, stopping once an ancestor already knew it had children there. */
	private void linkToParents(WorldSection child) {
		int lvl = child.lvl, x = child.x, y = child.y, z = child.z;
		while (lvl < WorldEngine.MAX_LOD_LAYER) {
			int bit = LodTree.childBit(x & 1, y & 1, z & 1);
			lvl++;
			x >>= 1;
			y >>= 1;
			z >>= 1;
			WorldSection parent = this.engine.acquire(lvl, x, y, z);
			try {
				byte before = parent.getNonEmptyChildren();
				if ((before & bit) != 0) return;
				parent._unsafeSetNonEmptyChildren((byte) (before | bit));
				this.engine.markDirty(parent, WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT, 0);
				if (before != 0) return;
			} finally {
				parent.release();
			}
		}
	}

	private int blockId(BlockState state) {
		return this.blockIds.computeIfAbsent(state, this.mapper::getIdForBlockState);
	}

	private int biomeId(Holder<Biome> biome) {
		return this.biomeIds.computeIfAbsent(biome, this.mapper::getIdForBiome);
	}
}
