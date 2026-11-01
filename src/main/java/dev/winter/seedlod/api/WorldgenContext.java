package dev.winter.seedlod.api;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

/**
 * The world generation of the dimension Seed LOD is generating. On a client connected to a server this is rebuilt
 * from the client's own mods and the seed the server sent, so there is no {@link #level()}.
 */
public interface WorldgenContext {
	ResourceKey<Level> dimension();

	long seed();

	RandomState randomState();

	NoiseGeneratorSettings settings();

	ChunkGenerator generator();

	BiomeSource biomeSource();

	/** Worldgen registries included. */
	RegistryAccess registries();

	/** Lowest block generated. */
	int minY();

	/** Blocks generated from {@link #minY()} up. */
	int height();

	int seaLevel();

	/**
	 * The live level, in singleplayer and on the server; null on a client connected to a server. Anything read from
	 * it there can't be read on the client, and the client only generates when its terrain matches the server's.
	 */
	@Nullable ServerLevel level();
}
