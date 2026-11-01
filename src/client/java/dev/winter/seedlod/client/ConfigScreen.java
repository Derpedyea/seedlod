package dev.winter.seedlod.client;

import dev.winter.seedlod.gen.TileGen;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class ConfigScreen {
	static Screen create(Screen parent) {
		SeedLodConfig config = SeedLodClient.config().copy();
		ConfigBuilder builder = ConfigBuilder.create().setParentScreen(parent)
			.setTitle(Component.translatable("seedlod.config.title"))
			.setSavingRunnable(() -> SeedLodClient.apply(config));
		ConfigEntryBuilder entries = builder.entryBuilder();
		ConfigCategory general = builder.getOrCreateCategory(Component.translatable("seedlod.config.general"));
		general.addEntry(entries.startBooleanToggle(Component.translatable("seedlod.config.enabled"), config.enabled)
			.setDefaultValue(true)
			.setTooltip(Component.translatable("seedlod.config.enabled.tooltip"))
			.setSaveConsumer(v -> config.enabled = v)
			.build());
		general.addEntry(entries.startIntSlider(Component.translatable("seedlod.config.threads"), config.threads, 0, Runtime.getRuntime().availableProcessors())
			.setDefaultValue(0)
			.setTextGetter(n -> n == 0 ? Component.translatable("seedlod.config.threads.auto", SeedLodConfig.autoThreads()) : Component.literal(String.valueOf(n)))
			.setTooltip(Component.translatable("seedlod.config.threads.tooltip"))
			.setSaveConsumer(v -> config.threads = v)
			.build());
		general.addEntry(entries.startIntSlider(Component.translatable("seedlod.config.detail"), (int) Math.round(config.detail * 100),
				(int) (SeedLodConfig.MIN_DETAIL * 100), (int) (SeedLodConfig.MAX_DETAIL * 100))
			.setDefaultValue(100)
			.setTextGetter(n -> Component.literal(n + "%"))
			.setTooltip(Component.translatable("seedlod.config.detail.tooltip"))
			.setSaveConsumer(v -> config.detail = v / 100.0)
			.build());
		general.addEntry(entries.startIntSlider(Component.translatable("seedlod.config.finestLevel"), config.finestLevel, 0, SeedLodConfig.MAX_LEVEL)
			.setDefaultValue(0)
			.setTextGetter(lvl -> Component.translatable("seedlod.config.finestLevel.value", 1 << lvl))
			.setTooltip(Component.translatable("seedlod.config.finestLevel.tooltip"))
			.setSaveConsumer(v -> config.finestLevel = v)
			.build());
		general.addEntry(entries.startIntSlider(Component.translatable("seedlod.config.biomeBlend"), config.biomeBlend, 0, TileGen.MAX_BIOME_BLEND)
			.setDefaultValue(TileGen.DEFAULT_BIOME_BLEND)
			.setTextGetter(n -> n == 0 ? Component.translatable("seedlod.config.biomeBlend.off") : Component.translatable("seedlod.config.biomeBlend.value", n))
			.setTooltip(Component.translatable("seedlod.config.biomeBlend.tooltip"))
			.setSaveConsumer(v -> config.biomeBlend = v)
			.build());
		return builder.build();
	}

	private ConfigScreen() {}
}
