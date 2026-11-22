package dev.winter.seedlod.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
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

		synchronized boolean allContained(int minChunkX, int minChunkZ, int chunksPerSide) {
			for (int dz = 0; dz < chunksPerSide; dz++)
				for (int dx = 0; dx < chunksPerSide; dx++) {
					if (!this.chunks.contains(key(minChunkX + dx, minChunkZ + dz))) return false;
				}
			return true;
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

		/** After a failed save: what {@link #takeChanged} handed out still needs saving. */
		synchronized void markChanged() {
			this.changed = true;
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
		return ourColumns(real, lvl, sectionX, sectionZ, raw, new boolean[32 * 32]);
	}

	static boolean[] ourColumns(RealChunks real, int lvl, int sectionX, int sectionZ, long[] raw, boolean[] ours) {
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

	static boolean allReal(RealChunks real, int lvl, int sectionX, int sectionZ) {
		int chunks = 2 << lvl;
		return real.allContained(sectionX * chunks, sectionZ * chunks, chunks);
	}

	static boolean allRealGroup(RealChunks real, int lvl, int groupX, int groupZ) {
		int chunks = 4 << lvl;
		return real.allContained(groupX * chunks, groupZ * chunks, chunks);
	}

	private static boolean hasUnvisitedNonAir(RealChunks real, int lvl, int sectionX, int sectionZ, long[] voxels) {
		int span = 16 >> lvl;
		int chunks = 2 << lvl;
		int baseChunkX = sectionX * chunks, baseChunkZ = sectionZ * chunks;
		for (int cz = 0; cz < chunks; cz++)
			for (int cx = 0; cx < chunks; cx++) {
				if (real.contains(baseChunkX + cx, baseChunkZ + cz)) continue;
				int x0 = cx * span, z0 = cz * span;
				for (int y = 0; y < 32; y++)
					for (int z = z0; z < z0 + span; z++) {
						int row = WorldSection.getIndex(x0, y, z);
						for (int x = 0; x < span; x++) if (!Mapper.isAir(voxels[row + x])) return true;
					}
			}
		return false;
	}

	/**
	 * For every octant in {@code octants} that holds blocks in {@code source} (32^3 voxels at the parent's level) in
	 * columns that aren't real chunks and has no child yet, writes a child made by doubling each voxel into our
	 * columns only and marks it in the parent. The caller holds the column lock and a reference to {@code parent}.
	 */
	public static void fillOctants(WorldEngine engine, RealChunks real, WorldSection parent, long[] source, int octants) {
		int childLvl = parent.lvl - 1;
		byte existing = parent.getNonEmptyChildren();
		byte added = 0;
		// Most calls (every real chunk section's path, once split) find nothing to do: allocate only when needed.
		long[] child = null;
		boolean[] ours = null;
		for (int o = 0; o < 8; o++) {
			int ox = o & 1, oy = (o >> 2) & 1, oz = (o >> 1) & 1;
			int bit = childBit(ox, oy, oz);
			if ((octants & bit) == 0 || (existing & bit) != 0) continue;
			int childX = parent.x * 2 + ox, childY = parent.y * 2 + oy, childZ = parent.z * 2 + oz;
			if (allReal(real, childLvl, childX, childZ)) continue;
			if (child == null) child = new long[VOXELS];
			int nonAir = upsample(source, ox, oy, oz, child);
			if (nonAir == 0 || !hasUnvisitedNonAir(real, childLvl, childX, childZ, child)) continue;
			WorldSection section = engine.acquire(childLvl, childX, childY, childZ);
			try {
				long[] raw = section._unsafeGetRawDataArray();
				if (ours == null) ours = new boolean[32 * 32];
				ourColumns(real, childLvl, childX, childZ, raw, ours);
				int delta = 0;
				boolean changed = false;
				boolean anyOursBlock = false;
				for (int i = 0; i < VOXELS; i++) {
					if (!ours[i & 1023]) continue;
					long next = child[i];
					if (!Mapper.isAir(next)) anyOursBlock = true;
					long old = raw[i];
					if (old == next) continue;
					raw[i] = next;
					if (childLvl == 0) delta += Mapper.isNotAirInt(next) - Mapper.isNotAirInt(old);
					changed = true;
				}
				if (childLvl == 0) {
					if (changed) {
						section.addNonEmptyBlockCount(delta);
						boolean stateChanged = section.updateLvl0State();
						engine.markDirty(section,
							WorldEngine.UPDATE_TYPE_BLOCK_BIT | (stateChanged ? WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT : 0),
							0b111111);
					}
					if (section.getNonEmptyChildren() != 0) added |= (byte) bit;
				} else {
					if (changed) engine.markDirty(section, WorldEngine.UPDATE_TYPE_BLOCK_BIT, 0b111111);
					if (anyOursBlock || section.getNonEmptyChildren() != 0) added |= (byte) bit;
				}
			} finally {
				section.release();
			}
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
		long[] child = null;
		boolean[] ours = null;
		for (int o = 0; o < 8; o++) {
			int ox = o & 1, oy = (o >> 2) & 1, oz = (o >> 1) & 1;
			if ((octants & childBit(ox, oy, oz)) == 0) continue;
			int childX = parent.x * 2 + ox, childY = parent.y * 2 + oy, childZ = parent.z * 2 + oz;
			if (allReal(real, 0, childX, childZ)) continue;
			WorldSection section = engine.acquireIfExists(0, childX, childY, childZ);
			if (section == null) continue;
			try {
				if (child == null) child = new long[VOXELS];
				upsample(source, ox, oy, oz, child);
				long[] raw = section._unsafeGetRawDataArray();
				if (ours == null) ours = new boolean[32 * 32];
				ourColumns(real, 0, section.x, section.z, raw, ours);
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
		RealChunks real = real(engine);
		for (int lvl = WorldEngine.MAX_LOD_LAYER; lvl >= 1; lvl--) {
			WorldSection section = engine.acquireIfExists(lvl, sx >> (lvl + 1), sy >> (lvl + 1), sz >> (lvl + 1));
			if (section == null) continue;
			try {
				byte children = section.getNonEmptyChildren();
				int shift = lvl;  // child coordinate = chunk section coordinate >> lvl
				int path = childBit((sx >> shift) & 1, (sy >> shift) & 1, (sz >> shift) & 1);
				if (children != 0 && (children & path) != 0) continue;
				long[] data = section._unsafeGetRawDataArray();
				if (children == 0 && !hasBlocks(data)) continue;
				fillOctants(engine, real, section, data, children == 0 ? 0xFF : path);
			} finally {
				section.release();
			}
		}
	}

	/**
	 * Writes {@code section}'s real mips into levels {@code fromLvl}..{@link WorldEngine#MAX_LOD_LAYER} after Voxy's
	 * {@link WorldUpdater#insertUpdate} broke out early on an unchanged lower level. Because Seed LOD generates coarse
	 * levels independently instead of mipping from level 0, matching at a lower level doesn't mean higher levels match.
	 */
	public static void syncUpperLevels(WorldEngine engine, VoxelizedSection section, int fromLvl) {
		int sx = section.x, sy = section.y, sz = section.z;
		boolean hasBlocks = section.lvl0NonAirCount > 0;
		for (int lvl = fromLvl; lvl <= WorldEngine.MAX_LOD_LAYER; lvl++) {
			int shift = lvl + 1;
			WorldSection worldSection = hasBlocks
				? engine.acquire(lvl, sx >> shift, sy >> shift, sz >> shift)
				: engine.acquireIfExists(lvl, sx >> shift, sy >> shift, sz >> shift);
			if (worldSection == null) continue;
			try {
				long res = WorldUpdater.insertSectionLvlIntoWorld(section, worldSection);
				if ((res & 1L) != 0) {
					int mask = 0;
					if (((sy ^ (sy - 1)) >> shift) != 0) mask |= 1;
					if (((sy ^ (sy + 1)) >> shift) != 0) mask |= 2;
					if (((sx ^ (sx - 1)) >> shift) != 0) mask |= 4;
					if (((sx ^ (sx + 1)) >> shift) != 0) mask |= 8;
					if (((sz ^ (sz - 1)) >> shift) != 0) mask |= 16;
					if (((sz ^ (sz + 1)) >> shift) != 0) mask |= 32;
					engine.markDirty(worldSection, WorldEngine.UPDATE_TYPE_BLOCK_BIT, mask);
				}
			} finally {
				worldSection.release();
			}
		}
	}
}
