package dev.winter.seedlod.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

public final class ModMenuEntry implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		// The screen is built with Cloth Config; without it Mod Menu just shows no config button.
		return FabricLoader.getInstance().isModLoaded("cloth-config") ? ConfigScreen::create : parent -> null;
	}
}
