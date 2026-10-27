package dev.winter.seedlod.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.jspecify.annotations.Nullable;

/**
 * Rules for keeping Voxy's section tree sound when it mixes our coarse sections with real chunk data.
 *
 * <p>Voxy treats a section with no children as a leaf and draws it at its own level. Real chunks only ever create
 * level 0 data, so any section above level 0 that is a leaf and holds blocks was written by us. When a section gains
 * a child, the renderer stops drawing it once close enough and draws its children instead, so every octant that has
 * blocks must have a child or it shows up as a hole. {@link #fillOctants} keeps that true by upsampling.
 */
public final class LodTree {
	public static final int VOXELS = 32 * 32 * 32;
	private static final ReentrantLock[] LOCKS = new ReentrantLock[256];

	static {
		for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new ReentrantLock();
	}

	/** One lock per level 4 column; everything we and Voxy's ingest touch for a position sits under one column. */
	public static ReentrantLock lock(int lvl4X, int lvl4Z) {
		return LOCKS[(lvl4X * 31 + lvl4Z) & (LOCKS.length - 1)];
	}

	/** What Voxy fills a section with until someone stores one there: open sky. */
	static final long UNWRITTEN = Mapper.airWithLight(15);

	/**
	 * The chunks Voxy holds real terrain for in one world. Everything else in Voxy is ours to overwrite. When the
	 * record is incomplete (Voxy had data before we kept it), anything already in a chunk counts as real too.
	 */
	public static final class RealChunks {
		private final LongSet chunks = new LongOpenHashSet();
		private boolean changed;
		volatile boolean complete;

		public synchronized void add(int chunkX, int chunkZ) {
			this.changed |= this.chunks.add(key(chunkX, chunkZ));
		}

		synchronized boolean contains(int chunkX, int chunkZ) {
			return this.chunks.contains(key(chunkX, chunkZ));
		}

		synchronized void addAll(long[] keys) {
			for (long key : keys) this.chunks.add(key);
		}

		/** The chunks, if any were added since the last call. */
		synchronized long @Nullable [] takeChanged() {
			if (!this.changed) return null;
			this.changed = false;
			return this.chunks.toLongArray();
		}

		synchronized long[] snapshot() {
			return this.chunks.toLongArray();
		}

		private static long key(int chunkX, int chunkZ) {
			return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
		}
	}

	private static final Map<WorldEngine, RealChunks> REAL = Collections.synchronizedMap(new WeakHashMap<>());

	public static RealChunks real(WorldEngine engine) {
		return REAL.computeIfAbsent(engine, e -> new RealChunks());
	}

	/**
	 * For each voxel column of a section at (lvl, sectionX, sectionZ), indexed x + z * 32: whether its data is ours
	 * to replace. A voxel never spans more than one chunk (levels 0 to 4 are 1 to 16 blocks).
	 */
	static boolean[] ourColumns(RealChunks real, int lvl, int sectionX, int sectionZ, long[] raw) {
		boolean[] ours = new boolean[32 * 32];
		int span = 16 >> lvl;
		for (int cz = 0; cz < 32; cz += span)
			for (int cx = 0; cx < 32; cx += span) {
				boolean free = !real.contains((sectionX * 32 + cx) << lvl >> 4, (sectionZ * 32 + cz) << lvl >> 4)
					&& (real.complete || onlySky(raw, cx, cz, span));
				for (int z = cz; z < cz + span; z++)
					for (int x = cx; x < cx + span; x++) ours[x + z * 32] = free;
			}
		return ours;
	}

	private static boolean onlySky(long[] raw, int minX, int minZ, int span) {
		for (int y = 0; y < 32; y++)
			for (int z = minZ; z < minZ + span; z++)
				for (int x = minX; x < minX + span; x++) {
					long id = raw[WorldSection.getIndex(x, y, z)];
					if (id != 0 && id != UNWRITTEN) return false;
				}
		return true;
	}

	/**
	 * Whether the level 4 sections around block (x, z) show terrain from chunks {@code real} doesn't know: Voxy had
	 * data in this world before we kept a record. A level 4 voxel is exactly one chunk.
	 */
	static boolean hasUnrecordedData(WorldEngine engine, RealChunks real, int x, int z) {
		int cx = x >> 9, cz = z >> 9;
		for (int sx = cx - 2; sx <= cx + 2; sx++)
			for (int sz = cz - 2; sz <= cz + 2; sz++)
				for (int sy = -1; sy <= 1; sy++) {
					WorldSection section = engine.acquireIfExists(WorldEngine.MAX_LOD_LAYER, sx, sy, sz);
					if (section == null) continue;
					try {
						long[] raw = section._unsafeGetRawDataArray();
						for (int i = 0; i < VOXELS; i++) {
							long id = raw[i];
							if (id != 0 && id != UNWRITTEN && !real.contains(sx * 32 + (i & 31), sz * 32 + ((i >> 5) & 31))) return true;
						}
					} finally {
						section.release();
					}
				}
		return false;
	}

	public static boolean hasBlocks(long[] data) {
		for (long id : data) if (!Mapper.isAir(id)) return true;
		return false;
	}

	/** Bit of a child within its parent's existence mask, matching {@link WorldSection#getChildIndex}. */
	public static int childBit(int octX, int octY, int octZ) {
		return 1 << WorldSection.getChildIndex(octX, octY, octZ);
	}

	/**
	 * For every octant in {@code octants} that holds blocks in {@code source} (32^3 voxels at the parent's level) and
	 * has no child yet, writes a child made by doubling each voxel and marks it in the parent. The caller holds the
	 * column lock and a reference to {@code parent}.
	 */
	public static void fillOctants(WorldEngine engine, WorldSection parent, long[] source, int octants) {
		int childLvl = parent.lvl - 1;
		byte existing = parent.getNonEmptyChildren();
		byte added = 0;
		long[] child = new long[VOXELS];
		for (int o = 0; o < 8; o++) {
			int ox = o & 1, oy = (o >> 2) & 1, oz = (o >> 1) & 1;
			int bit = childBit(ox, oy, oz);
			if ((octants & bit) == 0 || (existing & bit) != 0) continue;
			int nonAir = upsample(source, ox, oy, oz, child);
			if (nonAir == 0) continue;
			WorldSection section = engine.acquire(childLvl, parent.x * 2 + ox, parent.y * 2 + oy, parent.z * 2 + oz);
			try {
				long[] raw = section._unsafeGetRawDataArray();
				if (childLvl == 0) {
					int before = 0;
					for (long id : raw) before += Mapper.isNotAirInt(id);
					System.arraycopy(child, 0, raw, 0, VOXELS);
					section.addNonEmptyBlockCount(nonAir - before);
					section.updateLvl0State();
				} else {
					System.arraycopy(child, 0, raw, 0, VOXELS);
				}
				engine.markDirty(section, WorldEngine.DEFAULT_UPDATE_FLAGS, 0b111111);
			} finally {
				section.release();
			}
			added |= (byte) bit;
		}
		if (added != 0) {
			parent._unsafeSetNonEmptyChildren((byte) (existing | added));
			engine.markDirty(parent, WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT, 0);
		}
	}

	/**
	 * Level 0 children that hold real chunks usually cover only part of their 32-block section; fills the open sky
	 * Voxy left around them, where it's ours, from {@code source} doubled up.
	 */
	public static void patchLevel0Children(WorldEngine engine, RealChunks real, WorldSection parent, long[] source, int octants) {
		long[] child = new long[VOXELS];
		for (int o = 0; o < 8; o++) {
			int ox = o & 1, oy = (o >> 2) & 1, oz = (o >> 1) & 1;
			if ((octants & childBit(ox, oy, oz)) == 0) continue;
			WorldSection section = engine.acquireIfExists(0, parent.x * 2 + ox, parent.y * 2 + oy, parent.z * 2 + oz);
			if (section == null) continue;
			try {
				upsample(source, ox, oy, oz, child);
				long[] raw = section._unsafeGetRawDataArray();
				boolean[] ours = ourColumns(real, 0, section.x, section.z, raw);
				int added = 0;
				for (int i = 0; i < VOXELS; i++) {
					if (!ours[i & 1023] || (raw[i] != 0 && raw[i] != UNWRITTEN) || raw[i] == child[i]) continue;
					added += Mapper.isNotAirInt(child[i]) - Mapper.isNotAirInt(raw[i]);
					raw[i] = child[i];
				}
				if (added == 0) continue;
				section.addNonEmptyBlockCount(added);
				section.updateLvl0State();
				engine.markDirty(section, WorldEngine.UPDATE_TYPE_BLOCK_BIT, 0b111111);
			} finally {
				section.release();
			}
		}
	}

	/** Child voxel (x, y, z) takes the parent voxel covering it. Returns how many child voxels hold blocks. */
	private static int upsample(long[] source, int octX, int octY, int octZ, long[] out) {
		int nonAir = 0;
		for (int y = 0; y < 32; y++) {
			int sy = octY * 16 + (y >> 1);
			for (int z = 0; z < 32; z++) {
				int sz = octZ * 16 + (z >> 1);
				int row = WorldSection.getIndex(0, sy, sz) + octX * 16;
				int dst = WorldSection.getIndex(0, y, z);
				for (int x = 0; x < 32; x++) {
					long id = source[row + (x >> 1)];
					out[dst + x] = id;
					nonAir += Mapper.isNotAirInt(id);
				}
			}
		}
		return nonAir;
	}

	/**
	 * Before Voxy writes a real chunk section, makes sure the path down to it has no coarse leaf in the way: each of
	 * ours on the path is split into children, so the real data lands next to coarse siblings instead of next to
	 * holes. {@code sx, sy, sz} are chunk section coordinates, as in Voxy's VoxelizedSection.
	 */
	public static void prepareRealInsert(WorldEngine engine, int sx, int sy, int sz) {
		for (int lvl = WorldEngine.MAX_LOD_LAYER; lvl >= 1; lvl--) {
			WorldSection section = engine.acquire(lvl, sx >> (lvl + 1), sy >> (lvl + 1), sz >> (lvl + 1));
			try {
				long[] data = section._unsafeGetRawDataArray();
				boolean leaf = section.getNonEmptyChildren() == 0;
				if (leaf && !hasBlocks(data)) continue;
				int shift = lvl;  // child coordinate = chunk section coordinate >> lvl
				int path = childBit((sx >> shift) & 1, (sy >> shift) & 1, (sz >> shift) & 1);
				fillOctants(engine, section, data, leaf ? 0xFF : path);
			} finally {
				section.release();
			}
		}
	}
}
