package dev.winter.seedlod.gen;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/** Dev tool behind {@code -Dseedlod.debug=true}: times tile generation and draws the result as a top-down map. */
public final class Bench {
	public static String run(WorldgenView view, int lvl, int centerX, int centerZ, int tiles, int threads, Path image) throws Exception {
		int step = 1 << lvl;
		int span = Tile.SIZE * step;
		int firstX = Math.floorDiv(centerX, span) - tiles / 2;
		int firstZ = Math.floorDiv(centerZ, span) - tiles / 2;
		int px = tiles * Tile.SIZE;
		int[] pixels = new int[px * px];
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		ThreadLocal<TileGen> gens = ThreadLocal.withInitial(() -> new TileGen(view));
		long tileHash, groupHash;
		double ms, groupMs;
		try {
			// Untimed warm-up away from the measured area, so the timed tiles don't pay for JIT and class loading.
			List<Future<Long>> warmup = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int x = Math.floorDiv(firstX, 2) + tiles + i;
				warmup.add(pool.submit(() -> hash(gens.get().generateGroup(lvl, x, Math.floorDiv(firstZ, 2)))));
			}
			combine(warmup);

			// Tiles are hashed and drawn as they finish, never kept: holding them all would time the GC instead.
			long start = System.nanoTime();
			List<Future<Long>> jobs = new ArrayList<>();
			for (int tz = 0; tz < tiles; tz++)
				for (int tx = 0; tx < tiles; tx++) {
					int x = firstX + tx, z = firstZ + tz;
					jobs.add(pool.submit(() -> {
						Tile tile = gens.get().generate(lvl, x, z);
						draw(tile, pixels, px, (tile.tileX - firstX) * Tile.SIZE, (tile.tileZ - firstZ) * Tile.SIZE, step);
						return hash(tile);
					}));
				}
			tileHash = combine(jobs);
			ms = (System.nanoTime() - start) / 1e6;
			// The same area again in groups, as the client generates it (real trees up to RealTrees.MAX_LEVEL).
			long groupStart = System.nanoTime();
			List<Future<Long>> groups = new ArrayList<>();
			for (int gz = 0; gz < tiles / 2; gz++)
				for (int gx = 0; gx < tiles / 2; gx++) {
					int x = Math.floorDiv(firstX, 2) + gx, z = Math.floorDiv(firstZ, 2) + gz;
					groups.add(pool.submit(() -> hash(gens.get().generateGroup(lvl, x, z))));
				}
			groupHash = combine(groups);
			groupMs = (System.nanoTime() - groupStart) / 1e6;
		} finally {
			pool.shutdown();
		}

		BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
		img.setRGB(0, 0, px, px, pixels, 0, px);
		ImageIO.write(img, "png", image.toFile());
		return String.format("lvl %d: %d tiles (%d blocks wide) in %.0f ms on %d threads, %.1f ms/tile/thread; in groups %.1f ms/tile/thread; hash %016x/%016x -> %s",
			lvl, tiles * tiles, tiles * span, ms, threads, ms * threads / (tiles * tiles), groupMs * threads / (tiles / 2 * 2 * (tiles / 2 * 2)),
			tileHash, groupHash, image);
	}

	private static void draw(Tile tile, int[] pixels, int width, int offsetX, int offsetZ, int step) {
		for (int z = 0; z < Tile.SIZE; z++)
			for (int x = 0; x < Tile.SIZE; x++) {
				int y = tile.voxelsY - 1;
				while (y > 0 && tile.states[tile.index(x, y, z)] == null) y--;
				BlockState state = tile.states[tile.index(x, y, z)];
				int rgb = state == null ? 0 : state.getMapColor(null, null).col;
				if (state != null && state.getFluidState().isEmpty()) {
					float shade = 0.6F + 0.4F * Math.min(1, Math.max(0, ((tile.voxelMinY + y) * step - 40) / 160F));
					rgb = scale(rgb, shade);
				}
				pixels[offsetX + x + (offsetZ + z) * width] = rgb;
			}
	}

	/** Content hash that's stable across runs, to check an optimisation left the output alone. */
	private static long hash(Tile... tiles) {
		long h = 1;
		for (Tile tile : tiles) {
			for (int i = 0; i < tile.states.length; i++) {
				BlockState state = tile.states[i];
				h = h * 31 + (state == null ? -1 : Block.BLOCK_STATE_REGISTRY.getId(state));
				h = h * 31 + tile.light[i];
			}
			for (Holder<Biome> biome : tile.biomes) h = h * 31 + biome.unwrapKey().map(k -> k.identifier().toString().hashCode()).orElse(0);
		}
		return h;
	}

	private static long combine(List<Future<Long>> jobs) throws Exception {
		long h = 1;
		for (Future<Long> job : jobs) h = h * 1_000_003 + job.get();
		return h;
	}

	private static int scale(int rgb, float f) {
		int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f);
		return r << 16 | g << 8 | b;
	}
}
