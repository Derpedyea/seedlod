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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/** Dev tool behind {@code -Dseedlod.debug=true}: times tile generation and draws the result as a top-down map. */
public final class Bench {
	public static String run(WorldgenView view, int lvl, int centerX, int centerZ, int tiles, int threads, Path image) throws Exception {
		int step = 1 << lvl;
		int span = Tile.SIZE * step;
		int firstX = Math.floorDiv(centerX, span) - tiles / 2;
		int firstZ = Math.floorDiv(centerZ, span) - tiles / 2;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		ThreadLocal<TileGen> gens = ThreadLocal.withInitial(() -> new TileGen(view));
		long start = System.nanoTime();
		List<Future<Tile>> jobs = new ArrayList<>();
		for (int tz = 0; tz < tiles; tz++)
			for (int tx = 0; tx < tiles; tx++) {
				int x = firstX + tx, z = firstZ + tz;
				jobs.add(pool.submit(() -> gens.get().generate(lvl, x, z)));
			}
		List<Tile> done = new ArrayList<>();
		for (Future<Tile> job : jobs) done.add(job.get());
		long nanos = System.nanoTime() - start;
		// The same area again in groups, as the client generates it (real trees up to RealTrees.MAX_LEVEL).
		long groupStart = System.nanoTime();
		List<Future<Tile[]>> groups = new ArrayList<>();
		for (int gz = 0; gz < tiles / 2; gz++)
			for (int gx = 0; gx < tiles / 2; gx++) {
				int x = Math.floorDiv(firstX, 2) + gx, z = Math.floorDiv(firstZ, 2) + gz;
				groups.add(pool.submit(() -> gens.get().generateGroup(lvl, x, z)));
			}
		for (Future<Tile[]> job : groups) job.get();
		double groupMs = (System.nanoTime() - groupStart) / 1e6;
		pool.shutdown();

		int px = tiles * Tile.SIZE;
		BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
		for (Tile tile : done) {
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
					img.setRGB((tile.tileX - firstX) * Tile.SIZE + x, (tile.tileZ - firstZ) * Tile.SIZE + z, rgb);
				}
		}
		ImageIO.write(img, "png", image.toFile());
		double ms = nanos / 1e6;
		return String.format("lvl %d: %d tiles (%d blocks wide) in %.0f ms on %d threads, %.1f ms/tile/thread; in groups %.1f ms/tile/thread -> %s",
			lvl, tiles * tiles, tiles * span, ms, threads, ms * threads / (tiles * tiles), groupMs * threads / (tiles / 2 * 2 * (tiles / 2 * 2)), image);
	}

	private static int scale(int rgb, float f) {
		int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f);
		return r << 16 | g << 8 | b;
	}
}
