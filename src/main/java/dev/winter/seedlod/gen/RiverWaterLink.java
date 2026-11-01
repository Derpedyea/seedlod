package dev.winter.seedlod.gen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

/**
 * Winter Rivers' stream and lake water, when that mod is installed. Its channels are already in the final density we
 * sample; this says where water stands in them. Linked by name so Seed LOD runs without it.
 */
final class RiverWaterLink {
	static final long NONE = Long.MIN_VALUE;
	private static final @Nullable MethodHandle WATER, AT;

	static {
		MethodHandle water = null, at = null;
		if (FabricLoader.getInstance().isModLoaded("winterrivers")) {
			try {
				MethodHandles.Lookup lookup = MethodHandles.publicLookup();
				Class<?> api = Class.forName("dev.winter.rivers.api.RiversApi"), iface = Class.forName("dev.winter.rivers.api.RiversApi$Water");
				water = lookup.findStatic(api, "water", MethodType.methodType(iface, RandomState.class, NoiseGeneratorSettings.class))
					.asType(MethodType.methodType(Object.class, RandomState.class, NoiseGeneratorSettings.class));
				at = lookup.findVirtual(iface, "at", MethodType.methodType(long.class, int.class, int.class))
					.asType(MethodType.methodType(long.class, Object.class, int.class, int.class));
			} catch (ReflectiveOperationException e) {
				dev.winter.seedlod.SeedLod.LOG.warn("Winter Rivers is installed but its water API isn't usable: {}", e.toString());
				water = at = null;
			}
		}
		WATER = water;
		AT = at;
	}

	/** One per thread (the handle it returns isn't thread-safe); null without the mod or for worlds without streams. */
	static @Nullable Object create(WorldgenView view) {
		if (WATER == null) return null;
		try {
			Object water = (Object) WATER.invokeExact(view.randomState, view.settings);
			if (water == null) dev.winter.seedlod.SeedLod.LOG.info("Winter Rivers: no streams planned for this world");
			return water;
		} catch (Throwable t) {
			dev.winter.seedlod.SeedLod.LOG.warn("Winter Rivers water unavailable: {}", t.toString());
			return null;
		}
	}

	/** {@code top << 32 | bottom}: air in [bottom, top) is water; {@link #NONE} for none. */
	static long at(Object water, int x, int z) {
		try {
			return (long) AT.invokeExact(water, x, z);
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				dev.winter.seedlod.SeedLod.LOG.warn("Winter Rivers water lookup failed", t);
			}
			return NONE;
		}
	}

	private static volatile boolean warned;

	private RiverWaterLink() {}
}
