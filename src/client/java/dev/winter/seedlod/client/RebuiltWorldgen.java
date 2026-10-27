package dev.winter.seedlod.client;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.winter.seedlod.gen.WorldgenView;
import dev.winter.seedlod.net.SeedPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Util;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.DataPackConfig;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.jspecify.annotations.Nullable;

/**
 * On a server, the client never receives worldgen registries. It does have the same mods, so it loads their data
 * packs the way the create-world screen does, picks the packs the server has on, and builds the generator from
 * that. Server-only data packs or mods that patch worldgen at server start won't be seen, which is what the
 * fingerprint check catches.
 */
final class RebuiltWorldgen {
	private final HolderLookup.Provider registries;
	private final RegistryAccess access;
	private final Map<Holder<Biome>, List<HolderSet<PlacedFeature>>> features = new ConcurrentHashMap<>();
	private final SeedPayload payload;

	private RebuiltWorldgen(HolderLookup.Provider registries, RegistryAccess access, SeedPayload payload) {
		this.registries = registries;
		this.access = access;
		this.payload = payload;
	}

	/** Slow (a few seconds, reads every data pack); run off the main thread. */
	static RebuiltWorldgen load(SeedPayload payload) throws Exception {
		// A world-folder pack source is what makes Fabric add the mods' data packs to the repository.
		Path emptyWorldPacks = Files.createTempDirectory("seedlod-packs");
		PackRepository packs = ServerPacksSource.createPackRepository(emptyWorldPacks, Minecraft.getInstance().directoryValidator());
		packs.reload();
		net.minecraft.server.MinecraftServer.configurePackRepository(packs,
			new WorldDataConfiguration(new DataPackConfig(payload.enabledPacks(), payload.disabledPacks()), FeatureFlags.DEFAULT_FLAGS),
			false, false);
		try (MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, packs.openAllSelected())) {
			// Fabric's list, not vanilla's: mods add their own worldgen registries (lithostitched's noise configs) there.
			LayeredRegistryAccess<RegistryLayer> layers = RegistryLayer.createRegistryAccess();
			var staticTags = TagLoader.loadTagsForExistingRegistries(resources, layers.getLayer(RegistryLayer.STATIC));
			List<HolderLookup.RegistryLookup<?>> context = TagLoader.buildUpdatedLookups(layers.getAccessForLoading(RegistryLayer.WORLD), staticTags);
			RegistryAccess.Frozen world = RegistryDataLoader.load(resources, context, DynamicRegistries.getWorldRegistries(), Util.backgroundExecutor()).join();
			List<HolderLookup.RegistryLookup<?>> all = Stream.concat(context.stream(), world.listRegistries()).toList();
			return new RebuiltWorldgen(HolderLookup.Provider.create(all.stream()), layers.replaceFrom(RegistryLayer.WORLD, world).compositeAccess(), payload);
		} finally {
			Files.deleteIfExists(emptyWorldPacks);
		}
	}

	@Nullable WorldgenView view(String dimension, LevelHeightAccessor height) {
		SeedPayload.Dimension dim = this.payload.dimensions().get(dimension);
		if (dim == null) return null;
		RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, this.registries);
		ChunkGenerator generator = ChunkGenerator.CODEC.parse(ops, JsonParser.parseString(dim.generatorJson())).getOrThrow();
		if (!(generator instanceof NoiseBasedChunkGenerator noise)) return null;
		var settings = noise.generatorSettings().value();
		RandomState random = RandomState.create(this.registries.lookupOrThrow(Registries.NOISE), this.payload.seed(), settings);
		return new WorldgenView(random, settings, generator, height, this.payload.seed(), this::features, this.access, null);
	}

	/** A biome's features as the server has them; the client's own where the server sent none. */
	private List<HolderSet<PlacedFeature>> features(Holder<Biome> biome) {
		return this.features.computeIfAbsent(biome, b -> {
			List<List<String>> steps = b.unwrapKey().map(k -> this.payload.features().get(k.identifier().toString())).orElse(null);
			if (steps == null) return b.value().getGenerationSettings().features();
			var registry = this.registries.lookupOrThrow(Registries.PLACED_FEATURE);
			List<HolderSet<PlacedFeature>> out = new ArrayList<>();
			for (List<String> ids : steps) {
				List<Holder<PlacedFeature>> holders = new ArrayList<>();
				for (String id : ids) registry.get(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(id))).ifPresent(holders::add);
				out.add(HolderSet.direct(holders));
			}
			return out;
		});
	}

	long[] expectedFingerprint(String dimension) {
		return this.payload.dimensions().get(dimension).fingerprint().stream().mapToLong(Long::longValue).toArray();
	}
}
