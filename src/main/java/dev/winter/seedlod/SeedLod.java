package dev.winter.seedlod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.winter.seedlod.gen.Bench;
import net.minecraft.core.registries.Registries;
import dev.winter.seedlod.gen.Fingerprint;
import dev.winter.seedlod.net.SeedPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.jspecify.annotations.Nullable;
import dev.winter.seedlod.gen.WorldgenView;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SeedLod implements ModInitializer {
	public static final String MOD_ID = "seedlod";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	/** Built once per server start, off the main thread: generating the fingerprint tiles takes a moment. */
	private static volatile @Nullable CompletableFuture<SeedPayload> payload;

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(SeedPayload.TYPE, SeedPayload.CODEC);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			if (server.isDedicatedServer()) payload = CompletableFuture.supplyAsync(() -> buildPayload(server));
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> payload = null);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			CompletableFuture<SeedPayload> pending = payload;
			if (pending == null || !ServerPlayNetworking.canSend(handler.player, SeedPayload.TYPE)) return;
			pending.thenAccept(p -> server.execute(() -> {
				if (!handler.player.hasDisconnected()) ServerPlayNetworking.send(handler.player, p);
			}));
		});
		if (Boolean.getBoolean("seedlod.debug")) registerDebugCommands();
	}

	private static SeedPayload buildPayload(MinecraftServer server) {
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		Map<String, SeedPayload.Dimension> dimensions = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			WorldgenView view = WorldgenView.of(level);
			if (view == null) continue;
			try {
				JsonElement generator = ChunkGenerator.CODEC.encodeStart(ops, level.getChunkSource().getGenerator()).getOrThrow();
				dimensions.put(level.dimension().identifier().toString(), new SeedPayload.Dimension(generator.toString(), java.util.Arrays.stream(Fingerprint.of(view)).boxed().toList()));
			} catch (RuntimeException e) {
				LOG.warn("Not sharing {}: its generator doesn't serialize", level.dimension().identifier(), e);
			}
		}
		Map<String, List<List<String>>> features = new HashMap<>();
		int[] unnamed = {0};
		server.registryAccess().lookupOrThrow(Registries.BIOME).listElements().forEach(biome -> {
			List<List<String>> steps = new ArrayList<>();
			for (var step : biome.value().getGenerationSettings().features()) {
				List<String> ids = new ArrayList<>();
				for (var feature : step) {
					// Features added inline have no id to send; clients go without them.
					feature.unwrapKey().ifPresentOrElse(key -> ids.add(key.identifier().toString()), () -> unnamed[0]++);
				}
				steps.add(ids);
			}
			features.put(biome.key().identifier().toString(), steps);
		});
		if (unnamed[0] > 0) LOG.info("{} biome features have no id; Seed LOD clients won't grow those", unnamed[0]);
		var packs = server.getWorldData().getDataConfiguration().dataPacks();
		LOG.info("Sharing the seed with Seed LOD clients for {}", dimensions.keySet());
		return new SeedPayload(server.overworld().getSeed(), List.copyOf(packs.getEnabled()), List.copyOf(packs.getDisabled()), dimensions, features);
	}

	private static void registerDebugCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
			Commands.literal("seedlodbench").then(Commands.literal("run")
				.then(Commands.argument("lvl", IntegerArgumentType.integer(0, 4))
					.then(Commands.argument("tiles", IntegerArgumentType.integer(1, 256))
						.then(Commands.argument("threads", IntegerArgumentType.integer(1, 64))
							.executes(ctx -> {
								var source = ctx.getSource();
								WorldgenView view = WorldgenView.of(source.getLevel());
								if (view == null) {
									source.sendFailure(Component.literal("not a noise world"));
									return 0;
								}
								int lvl = IntegerArgumentType.getInteger(ctx, "lvl");
								int tiles = IntegerArgumentType.getInteger(ctx, "tiles");
								int threads = IntegerArgumentType.getInteger(ctx, "threads");
								var pos = source.getPosition();
								Path out = Path.of(System.getProperty("seedlod.benchDir", "."), "bench-l" + lvl + ".png");
								Thread.ofPlatform().name("seedlod-bench").start(() -> {
									try {
										LOG.info("[bench] {}", Bench.run(view, lvl, (int) pos.x, (int) pos.z, tiles, threads, out));
									} catch (Exception e) {
										LOG.error("[bench] failed", e);
									}
								});
								return 1;
							})))))));
	}
}
