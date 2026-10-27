package dev.winter.seedlod.client;

import dev.winter.seedlod.SeedLod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.jspecify.annotations.Nullable;

/**
 * Dev tool for testing without a person at the keyboard, on with {@code -Dseedlod.autopilot=<script>}. Once in a
 * world it runs the script's lines in order:
 * {@code cmd <command>}, {@code sleep <seconds>}, {@code idle} (wait for generation to finish),
 * {@code status}, {@code shot <name>}, {@code frames <seconds>} (frame time stats over that long), {@code quit}.
 */
final class Autopilot {
	private final Deque<String> lines = new ArrayDeque<>();
	private long waitUntil;
	private long idleSince;
	private long framesUntil;

	private Autopilot(Path script) throws IOException {
		for (String line : Files.readAllLines(script)) if (!line.isBlank() && !line.startsWith("#")) this.lines.add(line.trim());
	}

	static @Nullable Autopilot fromProperty() {
		String path = System.getProperty("seedlod.autopilot");
		if (path == null) return null;
		FrameStats.enabled = true;
		try {
			return new Autopilot(Path.of(path));
		} catch (IOException e) {
			SeedLod.LOG.error("[autopilot] can't read {}", path, e);
			return null;
		}
	}

	void tick(Minecraft mc, @Nullable Session session) {
		if (this.framesUntil != 0 && System.currentTimeMillis() >= this.framesUntil) {
			SeedLod.LOG.info("[autopilot] frames: {}", FrameStats.summary());
			this.framesUntil = 0;
		}
		if (mc.player == null || System.currentTimeMillis() < this.waitUntil || this.lines.isEmpty()) return;
		String line = this.lines.peek();
		String arg = line.contains(" ") ? line.substring(line.indexOf(' ') + 1) : "";
		switch (line.split(" ")[0]) {
			case "cmd" -> mc.player.connection.sendCommand(arg);
			case "sleep" -> this.waitUntil = System.currentTimeMillis() + (long) (Double.parseDouble(arg) * 1000);
			case "idle" -> {
				if (this.idleSince == 0) this.idleSince = System.currentTimeMillis();
				boolean noSession = session == null && System.currentTimeMillis() - this.idleSince > 60_000;
				if (!noSession && (session == null || !session.idle())) return;
				this.idleSince = 0;
			}
			case "status" -> SeedLod.LOG.info("[autopilot] {}", session == null ? "no session" : session.status());
			case "where" -> {
				var pos = mc.player.blockPosition();
				int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
				var real = mc.level.getBiome(pos.atY(ground)).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
				SeedLod.LOG.info("[autopilot] at {} ground {}: real biome {}, ours {}", pos, ground, real,
					session == null ? "no session" : session.describe(pos.getX(), ground, pos.getZ()));
			}
			case "probe" -> {
				String[] p = arg.split(" ");
				SeedLod.LOG.info("[autopilot] probe {}:{}", arg, session == null ? "no session"
					: session.probe(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
			}
			case "compare" -> {
				var server = mc.getSingleplayerServer();
				var pos = mc.player.blockPosition();
				String[] p = arg.split(" ");
				if (p.length == 2) pos = new net.minecraft.core.BlockPos(Integer.parseInt(p[0]), 0, Integer.parseInt(p[1]));
				SeedLod.LOG.info("[autopilot] compare: {}", session == null || server == null ? "no session/server"
					: session.compareTrees(server.getLevel(mc.level.dimension()), pos.getX(), pos.getZ()));
			}
			case "trees" -> SeedLod.LOG.info("[autopilot] trees:{}", session == null ? "no session" : session.trees());
			case "shot" -> Screenshot.grab(mc.gameDirectory, arg + ".png", mc.gameRenderer.mainRenderTarget(), 1,
				message -> SeedLod.LOG.info("[autopilot] {}", message.getString()));
			// Like frames, but the script carries on meanwhile (to measure while moving).
			case "watch" -> {
				FrameStats.reset();
				this.framesUntil = System.currentTimeMillis() + (long) (Double.parseDouble(arg) * 1000);
			}
			case "frames" -> {
				FrameStats.reset();
				this.framesUntil = System.currentTimeMillis() + (long) (Double.parseDouble(arg) * 1000);
				this.waitUntil = this.framesUntil;
			}
			case "quit" -> mc.stop();
			default -> SeedLod.LOG.warn("[autopilot] unknown line: {}", line);
		}
		SeedLod.LOG.info("[autopilot] {}", line);
		this.lines.poll();
	}
}
