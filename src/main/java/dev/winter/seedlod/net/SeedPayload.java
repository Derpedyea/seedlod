package dev.winter.seedlod.net;

import dev.winter.seedlod.SeedLod;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * What a client needs to rebuild the server's terrain itself: the seed, which data packs are on, each dimension's
 * chunk generator (as JSON referring to registry entries by id), a fingerprint of one generated tile per dimension so
 * the client can tell whether its rebuild matches, and each biome's placed features per decoration step (by id),
 * since mods change biome features on the server only.
 */
public record SeedPayload(long seed, List<String> enabledPacks, List<String> disabledPacks, Map<String, Dimension> dimensions,
	Map<String, List<List<String>>> features) implements CustomPacketPayload {

	public record Dimension(String generatorJson, List<Long> fingerprint) {
		static final StreamCodec<RegistryFriendlyByteBuf, Dimension> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(1 << 20), Dimension::generatorJson,
			ByteBufCodecs.LONG.apply(ByteBufCodecs.list()), Dimension::fingerprint,
			Dimension::new
		);
	}

	public static final Type<SeedPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SeedLod.MOD_ID, "seed"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SeedPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.LONG, SeedPayload::seed,
		ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SeedPayload::enabledPacks,
		ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SeedPayload::disabledPacks,
		ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, Dimension.CODEC), SeedPayload::dimensions,
		ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).apply(ByteBufCodecs.list())),
		SeedPayload::features,
		SeedPayload::new
	);

	@Override
	public Type<SeedPayload> type() {
		return TYPE;
	}
}
