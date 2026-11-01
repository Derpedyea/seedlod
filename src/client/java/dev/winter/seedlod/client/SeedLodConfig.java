package dev.winter.seedlod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.winter.seedlod.SeedLod;
import dev.winter.seedlod.gen.TileGen;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class SeedLodConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("seedlod.json");
	static final double MIN_DETAIL = 0.1, MAX_DETAIL = 1.0;
	static final int MAX_LEVEL = 4;

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
	/** Blocks grass, leaf and water colours are dithered across biome borders; 0 keeps hard edges. */
	public int biomeBlend = TileGen.DEFAULT_BIOME_BLEND;

	public static SeedLodConfig load() {
		SeedLodConfig config = new SeedLodConfig();
		try {
			if (Files.exists(FILE)) config = GSON.fromJson(Files.readString(FILE), SeedLodConfig.class);
		} catch (IOException | RuntimeException e) {
			SeedLod.LOG.warn("Couldn't read {}, using defaults", FILE, e);
		}
		config.clamp();
		config.save();
		return config;
	}

	void save() {
		try {
			Files.writeString(FILE, GSON.toJson(this));
		} catch (IOException e) {
			SeedLod.LOG.warn("Couldn't write {}", FILE, e);
		}
	}

	private void clamp() {
		this.threads = Math.max(0, this.threads);
		this.detail = Double.isNaN(this.detail) ? 1.0 : Math.clamp(this.detail, MIN_DETAIL, MAX_DETAIL);
		this.finestLevel = Math.clamp(this.finestLevel, 0, MAX_LEVEL);
		this.biomeBlend = Math.clamp(this.biomeBlend, 0, TileGen.MAX_BIOME_BLEND);
	}

	SeedLodConfig copy() {
		SeedLodConfig c = new SeedLodConfig();
		c.enabled = this.enabled;
		c.threads = this.threads;
		c.detail = this.detail;
		c.finestLevel = this.finestLevel;
		c.biomeBlend = this.biomeBlend;
		return c;
	}

	public int threadCount() {
		return this.threads > 0 ? this.threads : autoThreads();
	}

	static int autoThreads() {
		return Math.max(1, Runtime.getRuntime().availableProcessors() / 2 - 1);
	}
}
