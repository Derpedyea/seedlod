package dev.winter.seedlod.client;

import dev.winter.seedlod.SeedLod;
import dev.winter.seedlod.gen.WorldgenView;
import dev.winter.seedlod.gen.Fingerprint;
import dev.winter.seedlod.net.SeedPayload;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.Util;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

public final class SeedLodClient implements ClientModInitializer {
	private static final int SAVE_INTERVAL_TICKS = 20 * 60;

	private static SeedLodConfig config;
	private static @Nullable Session session;
	private static @Nullable ClientLevel sessionLevel;
	private static int ticks;
	private static @Nullable CompletableFuture<RebuiltWorldgen> remote;
	private static long rebuiltSeed;
	private static final @Nullable Autopilot AUTOPILOT = Autopilot.fromProperty();

	@Override
	public void onInitializeClient() {
		config = SeedLodConfig.load();
		ClientTickEvents.END_CLIENT_TICK.register(SeedLodClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			stop();
			remote = null;
		});
		ClientPlayNetworking.registerGlobalReceiver(SeedPayload.TYPE, (payload, context) -> onSeed(payload));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(
			ClientCommands.literal("seedlod")
				.then(ClientCommands.literal("status").executes(ctx -> {
					Session current = session;
					ctx.getSource().sendFeedback(Component.literal(current == null ? "Seed LOD: not running" : "Seed LOD: " + current.status()));
					return 1;
				}))
				.then(ClientCommands.literal("regen").executes(ctx -> {
					Session current = session;
					if (current != null) current.forget();
					ctx.getSource().sendFeedback(Component.literal("Seed LOD: regenerating everything in range"));
					return 1;
				}))));
	}

	private static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != sessionLevel) {
			stop();
			if (level != null && config.enabled) start(mc, level);
		}
		Session current = session;
		if (AUTOPILOT != null) AUTOPILOT.tick(mc, current);
		if (current == null || mc.gameRenderer.mainCamera() == null) return;
		var pos = mc.gameRenderer.mainCamera().position();
		current.tick(pos.x, pos.z, () -> DetailRule.current(mc, config));
		if (++ticks % SAVE_INTERVAL_TICKS == 0) current.saveIfDirty();
	}

	private static void start(Minecraft mc, ClientLevel level) {
		sessionLevel = level;
		if (!(VoxyCommon.getInstance() instanceof VoxyClientInstance voxy)) {
			sessionLevel = null;  // Voxy not up yet (or disabled); try again next tick
			return;
		}
		// A roofed dimension (the Nether) would show nothing but its ceiling from afar.
		if (level.dimensionType().hasCeiling()) return;
		WorldIdentifier id = WorldIdentifier.of(level);
		WorldEngine engine = id == null ? null : id.getOrCreateEngine();
		if (engine == null) return;
		Path doneFile = voxy.getStorageBasePath().resolve(id.getWorldId()).resolve("seedlod-done.bin");
		String dimension = level.dimension().identifier().toString();

		var server = mc.getSingleplayerServer();
		if (server != null) {
			ServerLevel serverLevel = server.getLevel(level.dimension());
			if (serverLevel == null) {
				sessionLevel = null;
				return;
			}
			WorldgenView view = WorldgenView.of(serverLevel);
			if (view != null) begin(level, view, engine, serverLevel.getSeed(), doneFile);
			return;
		}

		// On a server: wait for its seed, rebuild its worldgen, and only go ahead if a test tile comes out the same.
		CompletableFuture<RebuiltWorldgen> rebuilt = remote;
		if (rebuilt == null) return;  // seed not here yet: onSeed starts over when it arrives (a plain server never sends one)
		rebuilt.thenApplyAsync(worldgen -> {
			WorldgenView view = worldgen.view(dimension, level);
			if (view == null) return null;
			long[] mine = Fingerprint.of(view), theirs = worldgen.expectedFingerprint(dimension);
			if (!java.util.Arrays.equals(mine, theirs)) {
				String[] parts = {"shape", "surface", "biomes"};
				StringBuilder differ = new StringBuilder();
				for (int i = 0; i < parts.length; i++) if (mine[i] != theirs[i]) differ.append(' ').append(parts[i]);
				SeedLod.LOG.warn("Seed LOD: this client's terrain for {} doesn't match the server's ({} differ; different mods or data packs?), not generating", dimension, differ.toString().trim());
				return null;
			}
			return view;
		}, Util.backgroundExecutor()).whenComplete((view, error) -> mc.execute(() -> {
			if (error != null) SeedLod.LOG.error("Seed LOD: couldn't rebuild the server's worldgen", error);
			else if (view != null && sessionLevel == level && session == null) begin(level, view, engine, rebuiltSeed, doneFile);
		}));
	}

	private static void begin(ClientLevel level, WorldgenView view, WorldEngine engine, long seed, Path doneFile) {
		session = new Session(view, engine, seed, doneFile, config, VoxyCommon.getInstance().savingServiceRateLimiter);
		SeedLod.LOG.info("Seed LOD started for {} ({} threads)", level.dimension().identifier(), config.threadCount());
	}

	private static void onSeed(SeedPayload payload) {
		rebuiltSeed = payload.seed();
		remote = CompletableFuture.supplyAsync(() -> {
			try {
				return RebuiltWorldgen.load(payload);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, Util.backgroundExecutor());
		sessionLevel = null;  // retry starting now that the seed is here
	}

	private static void stop() {
		Session current = session;
		session = null;
		sessionLevel = null;
		if (current != null) current.stop();
	}
}
