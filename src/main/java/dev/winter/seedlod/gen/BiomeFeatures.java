package dev.winter.seedlod.gen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Each biome's placed features per decoration step, the way the server decorates chunks with them. On a client these
 * come from the server, since mods change biome features on the server only.
 */
public final class BiomeFeatures {
	static final int STEP = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();

	/** A tree-growing feature and its index in the step, which seeds it as it does in vanilla's decoration. */
	record Indexed(PlacedFeature feature, int index) {
	}

	private final Function<Holder<Biome>, List<HolderSet<PlacedFeature>>> lists;
	private final Map<Holder<Biome>, Set<PlacedFeature>> sets = new ConcurrentHashMap<>();
	/** Every tree-growing feature of the vegetation step, in the order vanilla runs them. */
	final List<Indexed> trees;

	public BiomeFeatures(Collection<Holder<Biome>> possibleBiomes, Function<Holder<Biome>, List<HolderSet<PlacedFeature>>> lists) {
		this.lists = lists;
		List<Indexed> trees = new ArrayList<>();
		var steps = FeatureSorter.buildFeaturesPerStep(List.copyOf(possibleBiomes), lists, true);
		if (steps.size() > STEP) {
			var step = steps.get(STEP);
			for (PlacedFeature feature : step.features())
				if (growsTrees(feature)) trees.add(new Indexed(feature, step.indexMapping().applyAsInt(feature)));
		}
		trees.sort((a, b) -> Integer.compare(a.index, b.index));
		this.trees = List.copyOf(trees);
	}

	List<HolderSet<PlacedFeature>> of(Holder<Biome> biome) {
		return this.lists.apply(biome);
	}

	/** What vanilla's biome filter asks: does this biome grow that feature? */
	boolean has(Holder<Biome> biome, PlacedFeature feature) {
		return this.sets.computeIfAbsent(biome, b -> {
			Set<PlacedFeature> all = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			for (HolderSet<PlacedFeature> step : this.lists.apply(b)) for (Holder<PlacedFeature> f : step) all.add(f.value());
			return all;
		}).contains(feature);
	}

	private static boolean growsTrees(PlacedFeature feature) {
		return feature.getFeatures().anyMatch(f -> f.value() instanceof TreeFeature || f.value() instanceof FallenTreeFeature);
	}
}
