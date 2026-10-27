package dev.winter.seedlod.client;

import dev.winter.seedlod.SeedLod;
import dev.winter.seedlod.gen.Niceness;
import dev.winter.seedlod.gen.Tile;
import dev.winter.seedlod.gen.TileGen;
import dev.winter.seedlod.gen.WorldgenView;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.cortex.voxy.common.world.WorldEngine;

/**
 * Generation for one world: plans 2x2 tile groups around the player at the levels Voxy will draw them at, runs them on
 * background threads and remembers which are done next to Voxy's own storage for that world.
 *
 * <p>Order: level 4 over the whole render distance first, so nothing is missing for long. Then every finer group by
 * how big it is on screen (distance over section size), so what is in front of the player sharpens first and a
 * group's parent always comes before it.
 */
final class Session {
	private static final int FILE_VERSION = 3;
	private static final int REPLAN_DISTANCE = 128;
	private static final boolean DEBUG = Boolean.getBoolean("seedlod.debug");

	private record Job(int lvl, int groupX, int groupZ, long priority) implements Comparable<Job> {
		@Override
		public int compareTo(Job other) {
			return Long.compare(this.priority, other.priority);
		}
	}

	private final WorldgenView view;
	private final WorldEngine engine;
	private final long seed;
	private final Path doneFile;
	private final BooleanSupplier canWrite;
	private final LodTree.RealChunks real;
	/** No saved record yet: whether Voxy's data is all recorded is decided at the first plan, by looking. */
	private boolean realUnknown = true;
	private final LongSet done = new LongOpenHashSet();
	private final LongSet inFlight = new LongOpenHashSet();
	private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
	private final List<Thread> workers = new ArrayList<>();
	private final AtomicInteger busy = new AtomicInteger();
	private final AtomicLong tilesDone = new AtomicLong();
	private final long startedAt = System.nanoTime();
	private volatile boolean stopped;
	private boolean dirty;
	private double planX = Double.NaN, planZ = Double.NaN;
	/** Plans and saves, one at a time and off the render thread: a plan walks every group in range. */
	private final ExecutorService housekeeping =
		Executors.newSingleThreadExecutor(task -> Thread.ofPlatform().name("seedlod-plan").daemon().unstarted(task));
	private final AtomicBoolean planning = new AtomicBoolean();

	/** @param canWrite Voxy's save-queue limiter: we hold off while it's behind, as its own importers do */
	Session(WorldgenView view, WorldEngine engine, long seed, Path doneFile, SeedLodConfig config, BooleanSupplier canWrite) {
		this.view = view;
		this.engine = engine;
		this.seed = seed;
		this.doneFile = doneFile;
		this.canWrite = canWrite;
		this.engine.acquireRef();
		this.real = LodTree.real(engine);
		this.loadDone();
		for (int i = 0; i < config.threadCount(); i++) {
			Thread thread = Thread.ofPlatform().name("seedlod-" + i).daemon().priority(Thread.MIN_PRIORITY).unstarted(this::work);
			this.workers.add(thread);
			thread.start();
		}
	}

	/** Called every client tick with the camera position; {@code rule} is only asked for when replanning. */
	void tick(double x, double z, Supplier<DetailRule> rule) {
		boolean moved = Double.isNaN(this.planX) || Math.abs(x - this.planX) > REPLAN_DISTANCE || Math.abs(z - this.planZ) > REPLAN_DISTANCE;
		if (!moved || !this.planning.compareAndSet(false, true)) return;
		this.planX = x;
		this.planZ = z;
		DetailRule current = rule.get();  // reads the window and options, so here on the render thread
		this.housekeep(() -> {
			try {
				long start = System.nanoTime();
				this.plan((int) x, (int) z, current);
				if (DEBUG) SeedLod.LOG.info("Planned {} jobs in {} ms", this.queue.size(), (System.nanoTime() - start) / 1_000_000);
			} catch (RuntimeException e) {
				SeedLod.LOG.error("Planning failed", e);
			} finally {
				this.planning.set(false);
			}
		});
	}

	/** Saves on the housekeeping thread, after any plan in progress. */
	void saveSoon() {
		this.housekeep(this::saveIfDirty);
	}

	private void housekeep(Runnable task) {
		try {
			this.housekeeping.execute(task);
		} catch (RejectedExecutionException e) {
			// Stopped meanwhile (stop() can run on the network thread when disconnecting): nothing left to do.
		}
	}

	private void plan(int x, int z, DetailRule rule) {
		if (this.realUnknown) {
			this.realUnknown = false;
			this.real.complete = !LodTree.hasUnrecordedData(this.engine, this.real, x, z);
			if (!this.real.complete) SeedLod.LOG.info("Voxy has terrain from before Seed LOD kept track here; leaving all of it alone");
			synchronized (this.done) {
				this.dirty = true;
			}
		}
		List<Job> jobs = new ArrayList<>();
		synchronized (this.done) {
			for (int lvl = WorldEngine.MAX_LOD_LAYER; lvl >= 0; lvl--) {
				int radius = rule.reach()[lvl];
				if (radius <= 0) continue;
				int groupSpan = 2 * Tile.SIZE << lvl;
				int min = Math.floorDiv(-radius, groupSpan) - 1, max = Math.floorDiv(radius, groupSpan) + 1;
				int cx = Math.floorDiv(x, groupSpan), cz = Math.floorDiv(z, groupSpan);
				for (int gz = cz + min; gz <= cz + max; gz++)
					for (int gx = cx + min; gx <= cx + max; gx++) {
						long dx = (long) gx * groupSpan + groupSpan / 2 - x, dz = (long) gz * groupSpan + groupSpan / 2 - z;
						double dist = Math.max(0, Math.sqrt(dx * dx + dz * dz) - groupSpan * 0.71);
						if (dist > radius) continue;
						long key = key(lvl, gx, gz);
						if (this.done.contains(key) || this.inFlight.contains(key)) continue;
						long onScreen = (long) (dist * 1024 / (Tile.SIZE << lvl));
						jobs.add(new Job(lvl, gx, gz, lvl == WorldEngine.MAX_LOD_LAYER ? onScreen : (1L << 40) + onScreen));
					}
			}
		}
		this.queue.clear();
		this.queue.addAll(jobs);
	}

	private void work() {
		Niceness.lowerCurrentThread(10);
		try (TileGen gen = new TileGen(this.view)) {
			VoxyWriter writer = new VoxyWriter(this.engine);
			while (!this.stopped) {
				Job job = this.queue.take();
				long key = key(job.lvl, job.groupX, job.groupZ);
				synchronized (this.done) {
					this.inFlight.add(key);
				}
				this.busy.incrementAndGet();
				try {
					Tile[] group = gen.generateGroup(job.lvl, job.groupX, job.groupZ);
					while (!this.canWrite.getAsBoolean() && !this.stopped) Thread.sleep(20);
					if (this.stopped || !this.engine.isLive()) break;
					writer.writeGroup(group);
					this.tilesDone.addAndGet(4);
					synchronized (this.done) {
						this.done.add(key);
						this.dirty = true;
					}
				} catch (RuntimeException e) {
					SeedLod.LOG.error("Generating level {} group {} {} failed", job.lvl, job.groupX, job.groupZ, e);
				} finally {
					synchronized (this.done) {
						this.inFlight.remove(key);
					}
					this.busy.decrementAndGet();
				}
			}
		} catch (InterruptedException ignored) {
		}
	}

	String status() {
		int[] perLevel = new int[WorldEngine.MAX_LOD_LAYER + 1];
		synchronized (this.done) {
			for (long key : this.done) perLevel[(int) (key >>> 60)]++;
		}
		double secs = (System.nanoTime() - this.startedAt) / 1e9;
		return String.format("queued %d, busy %d/%d, done groups l0..l4 %d/%d/%d/%d/%d, %.1f tiles/s",
			this.queue.size(), this.busy.get(), this.workers.size(), perLevel[0], perLevel[1], perLevel[2], perLevel[3], perLevel[4],
			this.tilesDone.get() / secs);
	}

	/** Dev aid: the biome and tree mix we compute at a block. */
	String describe(int x, int y, int z) {
		var biome = this.view.biomes.createResolver(this.view.randomState.createClimateSampler(net.minecraft.world.level.levelgen.densityfunction.SamplerContext.EMPTY_UNCACHED))
			.getNoiseBiome(x >> 2, y >> 2, z >> 2);
		var mix = this.view.trees.of(biome);
		return biome.unwrapKey().map(k -> k.identifier().toString()).orElse("?") + String.format(" %.1f trees/chunk", mix.perChunk());
	}

	/** Dev aid: the section tree over a column, finest level first: per section y, child bits and solid voxels. */
	String probe(int x, int z) {
		StringBuilder out = new StringBuilder();
		for (int lvl = 0; lvl <= WorldEngine.MAX_LOD_LAYER; lvl++) {
			int shift = 5 + lvl;
			int groupSpan = 2 * Tile.SIZE << lvl;
			boolean isDone;
			synchronized (this.done) {
				isDone = this.done.contains(key(lvl, Math.floorDiv(x, groupSpan), Math.floorDiv(z, groupSpan)));
			}
			out.append(String.format("%n  l%d done=%s", lvl, isDone));
			for (int sy = -64 >> shift; sy <= 320 >> shift; sy++) {
				var section = this.engine.acquireIfExists(lvl, x >> shift, sy, z >> shift);
				if (section == null) continue;
				try {
					int solid = 0;
					for (long id : section._unsafeGetRawDataArray()) solid += me.cortex.voxy.common.world.other.Mapper.isNotAirInt(id);
					out.append(String.format(" %d:%02x/%d", sy, section.getNonEmptyChildren() & 0xFF, solid));
				} finally {
					section.release();
				}
			}
		}
		return out.toString();
	}

	/**
	 * Dev aid: the level 0 group at block (x, z) as we generate it against the real chunks, counting logs and leaves:
	 * in both, only real, only ours.
	 */
	String compareTrees(net.minecraft.server.level.ServerLevel level, int x, int z) {
		Tile[] group;
		try (TileGen gen = new TileGen(this.view)) {
			group = gen.generateGroup(0, Math.floorDiv(x, 64), Math.floorDiv(z, 64));
		}
		int both = 0, onlyReal = 0, onlyOurs = 0, groundOff = 0;
		var diffs = new java.util.TreeMap<String, Integer>();
		var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
		// Top-down, 8 px a column: green both, red only real, blue only ours, grey no trees; a dark dot where the ground differs.
		var image = new java.awt.image.BufferedImage(64 * 8, 64 * 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
		for (int t = 0; t < group.length; t++) {
			Tile tile = group[t];
			for (int tz = 0; tz < Tile.SIZE; tz++)
				for (int tx = 0; tx < Tile.SIZE; tx++) {
					int bx = tile.tileX * Tile.SIZE + tx, bz = tile.tileZ * Tile.SIZE + tz;
					var chunk = level.getChunk(bx >> 4, bz >> 4);
					int b = 0, r0 = 0, o0 = 0, ourGround = Integer.MIN_VALUE, realGround = Integer.MIN_VALUE;
					var ground = net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR.isOpaque();
					for (int y = 0; y < tile.voxelsY; y++) {
						var ours = tile.states[tile.index(tx, y, tz)];
						var real = chunk.getBlockState(pos.set(bx, tile.voxelMinY + y, bz));
						boolean o = ours != null && isTree(ours), r = isTree(real);
						if (o && r) b++;
						else if (r) r0++;
						else if (o) o0++;
						if (ours != null && !o && ground.test(ours)) ourGround = tile.voxelMinY + y + 1;
						if (!r && ground.test(real)) realGround = tile.voxelMinY + y + 1;
					}
					both += b;
					onlyReal += r0;
					onlyOurs += o0;
					boolean off = realGround != ourGround;
					if (off) {
						groundOff++;
						var realTop = chunk.getBlockState(pos.set(bx, realGround - 1, bz));
						var above = chunk.getBlockState(pos.set(bx, realGround, bz));
						int d = realGround - ourGround;
						diffs.merge((d < -8 ? "<-8" : d > 8 ? ">8" : String.valueOf(d)) + " real " + realTop.getBlock() + " under " + above.getBlock(), 1, Integer::sum);
					}
					int rgb = b >= Math.max(r0, o0) && b > 0 ? 0x30c030 : r0 > o0 ? 0xe03030 : o0 > 0 ? 0x3060ff : 0x808080;
					int px = (bx - group[0].tileX * Tile.SIZE) * 8, pz = (bz - group[0].tileZ * Tile.SIZE) * 8;
					for (int dz = 0; dz < 8; dz++)
						for (int dx = 0; dx < 8; dx++) image.setRGB(px + dx, pz + dz, off && dx > 2 && dx < 5 && dz > 2 && dz < 5 ? 0x101010 : rgb);
				}
		}
		try {
			javax.imageio.ImageIO.write(image, "png", java.nio.file.Path.of("compare-" + x + "-" + z + ".png").toFile());
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		var top = diffs.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(8).map(e -> e.getValue() + "x " + e.getKey()).toList();
		return String.format("tree blocks: both %d, only real %d, only ours %d; ground differs in %d/4096 columns: %s", both, onlyReal, onlyOurs, groundOff, top);
	}

	private static boolean isTree(net.minecraft.world.level.block.state.BlockState state) {
		return state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES);
	}

	String trees() {
		return this.view.trees.summary();
	}

	boolean idle() {
		return !Double.isNaN(this.planX) && !this.planning.get() && this.queue.isEmpty() && this.busy.get() == 0;
	}

	void forget() {
		synchronized (this.done) {
			this.done.clear();
			this.dirty = true;
		}
		this.planX = Double.NaN;
	}

	void saveIfDirty() {
		long[] keys;
		long[] realKeys = this.real.takeChanged();
		synchronized (this.done) {
			if (!this.dirty && realKeys == null) return;
			this.dirty = false;
			keys = this.done.toLongArray();
		}
		if (realKeys == null) realKeys = this.real.snapshot();
		// Written aside and moved over, so a failed write can't leave half a record behind.
		Path temp = this.doneFile.resolveSibling(this.doneFile.getFileName() + ".tmp");
		try {
			Files.createDirectories(this.doneFile.getParent());
			try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(temp)))) {
				out.writeInt(FILE_VERSION);
				out.writeLong(this.seed);
				out.writeBoolean(this.real.complete);
				out.writeInt(keys.length);
				for (long key : keys) out.writeLong(key);
				out.writeInt(realKeys.length);
				for (long key : realKeys) out.writeLong(key);
			}
			Files.move(temp, this.doneFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			SeedLod.LOG.warn("Couldn't save {}", this.doneFile, e);
			// Try again next time, rather than lose the real chunks (they'd be taken for ours after a restart).
			this.real.markChanged();
			synchronized (this.done) {
				this.dirty = true;
			}
		}
	}

	/**
	 * Version 2 files (Seed LOD 0.2) kept no record of real chunks, so that record stays incomplete; and 0.2 marked
	 * level 0 done without writing it where Voxy had nothing yet, so all of it is generated again.
	 */
	private void loadDone() {
		if (!Files.exists(this.doneFile)) return;
		try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(this.doneFile)))) {
			int version = in.readInt();
			if ((version != 2 && version != FILE_VERSION) || in.readLong() != this.seed) return;
			this.realUnknown = false;
			this.real.complete = version == FILE_VERSION && in.readBoolean();
			if (version == FILE_VERSION) {
				for (int i = in.readInt(); i > 0; i--) this.done.add(in.readLong());
				long[] realKeys = new long[in.readInt()];
				for (int i = 0; i < realKeys.length; i++) realKeys[i] = in.readLong();
				this.real.addAll(realKeys);
			}
		} catch (IOException e) {
			SeedLod.LOG.warn("Couldn't read {}, starting over", this.doneFile, e);
		}
	}

	void stop() {
		this.stopped = true;
		this.queue.clear();
		for (Thread thread : this.workers) thread.interrupt();
		for (Thread thread : this.workers) {
			try {
				thread.join(10_000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		this.housekeeping.shutdown();
		try {
			this.housekeeping.awaitTermination(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		this.saveIfDirty();
		this.engine.releaseRef();
	}

	private static long key(int lvl, int groupX, int groupZ) {
		return (long) lvl << 60 | (groupX & 0x3FFFFFFL) << 30 | (groupZ & 0x3FFFFFFL);
	}
}
