package dev.winter.seedlod.gen;

import dev.winter.seedlod.api.WorldgenContext;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.Nullable;

/**
 * Everything the tile generator needs from one dimension's world generation: the seeded noise, the biome source and
 * the material (surface) rules. Built either from a live server level (singleplayer, or the server itself) or from
 * registries the client rebuilt from its own mods plus a seed the server sent.
 */
public final class WorldgenView implements WorldgenContext {
	public final ResourceKey<Level> dimension;
	public final RandomState randomState;
	public final NoiseGeneratorSettings settings;
	public final BiomeSource biomes;
	public final WorldGenerationContext genContext;
	public final int minY;
	public final int height;
	public final int seaLevel;
	public final BlockState defaultBlock;
	public final BlockState defaultFluid;
	public final ChunkGenerator generator;
	public final long seed;
	public final BiomeFeatures features;
	public final BiomeTrees trees;
	public final RegistryAccess registries;
	/** The live level this was built from, or null when rebuilt on a client. */
	public final @Nullable ServerLevel level;

	/** @param features each biome's placed features per decoration step, as the server has them */
	public WorldgenView(ResourceKey<Level> dimension, RandomState randomState, NoiseGeneratorSettings settings, ChunkGenerator generator, LevelHeightAccessor heightAccessor,
		long seed, Function<Holder<Biome>, List<HolderSet<PlacedFeature>>> features, RegistryAccess registries, @Nullable ServerLevel level) {
		this.dimension = dimension;
		this.randomState = randomState;
		this.registries = registries;
		this.level = level;
		this.generator = generator;
		this.seed = seed;
		BiomeSource biomes = generator.getBiomeSource();
		this.features = new BiomeFeatures(biomes.possibleBiomes(), features);
		this.trees = new BiomeTrees(this.features);
		this.settings = settings;
		this.biomes = biomes;
		this.genContext = new WorldGenerationContext(generator, heightAccessor);
		this.minY = Math.max(settings.noiseSettings().minY(), heightAccessor.getMinY());
		this.height = Math.min(settings.noiseSettings().minY() + settings.noiseSettings().height(), heightAccessor.getMaxY() + 1) - this.minY;
		this.seaLevel = settings.seaLevel();
		this.defaultBlock = settings.defaultBlock();
		this.defaultFluid = settings.defaultFluid();
	}

	/** Null when the level isn't generated from noise (flat, debug, or a modded generator we don't understand). */
	public static @Nullable WorldgenView of(ServerLevel level) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		if (!(generator instanceof NoiseBasedChunkGenerator noise)) return null;
		return new WorldgenView(level.dimension(), level.getChunkSource().randomState(), noise.generatorSettings().value(), generator, level, level.getSeed(),
			biome -> biome.value().getGenerationSettings().features(), level.registryAccess(), level);
	}

	@Override
	public ResourceKey<Level> dimension() {
		return this.dimension;
	}

	@Override
	public long seed() {
		return this.seed;
	}

	@Override
	public RandomState randomState() {
		return this.randomState;
	}

	@Override
	public NoiseGeneratorSettings settings() {
		return this.settings;
	}

	@Override
	public ChunkGenerator generator() {
		return this.generator;
	}

	@Override
	public BiomeSource biomeSource() {
		return this.biomes;
	}

	@Override
	public RegistryAccess registries() {
		return this.registries;
	}

	@Override
	public int minY() {
		return this.minY;
	}

	@Override
	public int height() {
		return this.height;
	}

	@Override
	public int seaLevel() {
		return this.seaLevel;
	}

	@Override
	public @Nullable ServerLevel level() {
		return this.level;
	}
}
