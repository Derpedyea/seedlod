package dev.winter.seedlod.api;

import dev.winter.seedlod.gen.Hooks;
import dev.winter.seedlod.gen.RealTrees;
import net.minecraft.world.level.LevelReader;
import org.jspecify.annotations.Nullable;

/**
 * For code that runs inside Seed LOD's generation without registering anything: features (Seed LOD grows trees by
 * running their features against its own level) and mixins into worldgen. Touch this class only when Seed LOD is
 * loaded ({@code FabricLoader.getInstance().isModLoaded("seedlod")}).
 */
public final class SeedLodApi {
	private SeedLodApi() {
	}

	/**
	 * Whether a feature is being placed in Seed LOD's terrain rather than a chunk. That level holds generated voxels
	 * and nothing else: no chunks, block entities, ticks or light, and above level 0 a block stands for a voxel of
	 * several.
	 */
	public static boolean isSeedLodLevel(LevelReader level) {
		return level instanceof RealTrees.Staged;
	}

	/**
	 * The world being generated, while this thread is generating Seed LOD terrain (terrain modifiers, trees);
	 * otherwise null. Lets code with no level at hand tell, for instance whether a live server level exists:
	 * {@code current() != null && current().level() == null} means a client on a server.
	 */
	public static @Nullable WorldgenContext current() {
		return Hooks.current();
	}
}
