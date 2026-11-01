package dev.winter.seedlod.gen;

import dev.winter.seedlod.SeedLod;
import dev.winter.seedlod.api.SeedLodPlugin;
import dev.winter.seedlod.api.SeedLodRegistrar;
import dev.winter.seedlod.api.TerrainModifier;
import dev.winter.seedlod.api.TreeShape;
import dev.winter.seedlod.api.WorldgenContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.jspecify.annotations.Nullable;

/** What other mods registered through the {@code seedlod} entrypoint (see {@link SeedLodPlugin}). Internal. */
public final class Hooks {
	public static final String ENTRYPOINT = "seedlod";
	private static final ThreadLocal<@Nullable WorldgenView> CURRENT = new ThreadLocal<>();
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	record Modifier(Identifier id, TerrainModifier.Factory factory) {
	}

	record Tree<F extends Feature>(Class<F> type, TreeShape<? super F> shape) {
		/** Null if the plugin shows no canopy for it, or failed to say. */
		TreeShape.@Nullable Canopy describe(Feature feature) {
			try {
				return this.shape.describe(this.type.cast(feature), RandomSource.create(42));
			} catch (RuntimeException e) {
				report("Seed LOD couldn't describe a " + this.type.getName() + " for far terrain; leaving it out", e);
				return null;
			}
		}
	}

	/** Sorted by id, so a server and its clients run them in the same order. */
	final List<Modifier> modifiers;
	private final List<Tree<?>> trees;

	private Hooks(List<Modifier> modifiers, List<Tree<?>> trees) {
		this.modifiers = modifiers;
		this.trees = trees;
	}

	/** Plugins register on first use, by which time every mod has initialized. */
	private static final class Loaded {
		static final Hooks INSTANCE = load();
	}

	static Hooks get() {
		return Loaded.INSTANCE;
	}

	private static Hooks load() {
		List<Modifier> modifiers = new ArrayList<>();
		List<Tree<?>> trees = new ArrayList<>();
		Set<Identifier> ids = new HashSet<>();
		for (EntrypointContainer<SeedLodPlugin> container : FabricLoader.getInstance().getEntrypointContainers(ENTRYPOINT, SeedLodPlugin.class)) {
			String mod = container.getProvider().getMetadata().getId();
			// A plugin that throws registers nothing, rather than half of what it meant to.
			List<Modifier> ownModifiers = new ArrayList<>();
			List<Tree<?>> ownTrees = new ArrayList<>();
			boolean[] open = {true};
			SeedLodRegistrar registrar = new SeedLodRegistrar() {
				@Override
				public void terrainModifier(Identifier id, TerrainModifier.Factory factory) {
					check();
					if (!ids.add(id)) throw new IllegalArgumentException("Seed LOD terrain modifier " + id + " is registered twice");
					ownModifiers.add(new Modifier(id, factory));
				}

				@Override
				public <F extends Feature> void treeFeature(Class<F> type, TreeShape<? super F> shape) {
					check();
					ownTrees.add(new Tree<>(type, shape));
				}

				private void check() {
					if (!open[0]) throw new IllegalStateException("Register with Seed LOD from SeedLodPlugin.register only");
				}
			};
			try {
				container.getEntrypoint().register(registrar);
				modifiers.addAll(ownModifiers);
				trees.addAll(ownTrees);
			} catch (RuntimeException | LinkageError e) {
				ownModifiers.forEach(m -> ids.remove(m.id()));
				SeedLod.LOG.error("{}'s Seed LOD plugin failed; going on without it", mod, e);
			} finally {
				open[0] = false;
			}
		}
		modifiers.sort(Comparator.comparing(m -> m.id().toString()));
		if (!modifiers.isEmpty() || !trees.isEmpty())
			SeedLod.LOG.info("Seed LOD hooks: terrain modifiers {}, tree features {}",
				modifiers.stream().map(Modifier::id).toList(), trees.stream().map(t -> t.type().getName()).toList());
		return new Hooks(List.copyOf(modifiers), List.copyOf(trees));
	}

	/** The tree a plugin registered this feature's class as, or null. */
	@Nullable Tree<?> tree(Feature feature) {
		for (Tree<?> tree : this.trees) if (tree.type().isInstance(feature)) return tree;
		return null;
	}

	/** Marks this thread as generating for {@link #current()}; returns what to {@link #exit} with. */
	static @Nullable WorldgenView enter(WorldgenView view) {
		WorldgenView previous = CURRENT.get();
		CURRENT.set(view);
		return previous;
	}

	static void exit(@Nullable WorldgenView previous) {
		CURRENT.set(previous);
	}

	public static @Nullable WorldgenContext current() {
		return CURRENT.get();
	}

	/** Logs a mod's failure once per kind, not once per tile. */
	static void report(String message, Throwable error) {
		if (REPORTED.add(message + error)) SeedLod.LOG.warn(message, error);
	}
}
