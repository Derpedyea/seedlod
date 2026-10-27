package dev.winter.seedlod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.winter.seedlod.SeedLod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class SeedLodConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("seedlod.json");

	public boolean enabled = true;
	/** 0 picks half the cores, minus one for the game. */
	public int threads = 0;
	/**
	 * Scales how far each finer level reaches. 1 generates the detail Voxy will actually draw at your window size and
	 * field of view; lower is faster to fill and coarser up close.
	 */
	public double detail = 1.0;
	/** Finest level generated: 0 is one block per voxel, as close up as Voxy draws. */
	public int finestLevel = 0;

	public static SeedLodConfig load() {
		SeedLodConfig config = new SeedLodConfig();
		try {
			if (Files.exists(FILE)) config = GSON.fromJson(Files.readString(FILE), SeedLodConfig.class);
			Files.writeString(FILE, GSON.toJson(config));
		} catch (IOException | RuntimeException e) {
			SeedLod.LOG.warn("Couldn't read {}, using defaults", FILE, e);
		}
		return config;
	}

	public int threadCount() {
		return this.threads > 0 ? this.threads : Math.max(1, Runtime.getRuntime().availableProcessors() / 2 - 1);
	}
}
