package dev.winter.seedlod.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.foliageplacers.PoplarFoliagePlacer;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

/**
 * What trees a biome grows, on average: read from its vegetation features rather than generated. Counts come from
 * the placement modifiers (count, rarity, noise counts), shapes from the trunk and foliage placers. Exact positions
 * don't matter at LOD distances; how much canopy, how tall and what colour do. Only far levels use this; near ones
 * grow the real trees ({@link RealTrees}).
 */
public final class BiomeTrees {
	/** One kind of tree and how many of it grow per 16x16 chunk. */
	public record Kind(float perChunk, float height, float crownRadius, float crownHeight, BlockState leaves) {
	}

	public record Mix(List<Kind> kinds, float perChunk) {
		static final Mix NONE = new Mix(List.of(), 0);

		public static Mix of(List<Kind> kinds) {
			float total = 0;
			for (Kind kind : kinds) total += kind.perChunk;
			return new Mix(List.copyOf(kinds), total);
		}
	}

	private static final int SAMPLES = 16;
	private final BiomeFeatures features;
	private final Map<Holder<Biome>, Mix> cache = new ConcurrentHashMap<>();

	BiomeTrees(BiomeFeatures features) {
		this.features = features;
	}

	public Mix of(Holder<Biome> biome) {
		return this.cache.computeIfAbsent(biome, b -> this.compute(this.features.of(b)));
	}

	/** Dev aid: what was read for each biome seen so far. */
	public String summary() {
		StringBuilder out = new StringBuilder();
		this.cache.forEach((biome, mix) -> {
			out.append('\n').append(biome.unwrapKey().map(k -> k.identifier().toString()).orElse("?")).append(String.format(": %.1f/chunk", mix.perChunk));
			for (Kind k : mix.kinds) out.append(String.format(" [%.2f h%.0f r%.1f %s]", k.perChunk, k.height, k.crownRadius, k.leaves.getBlock().getDescriptionId()));
		});
		return out.toString();
	}

	private Mix compute(List<HolderSet<PlacedFeature>> steps) {
		List<Kind> kinds = new ArrayList<>();
		int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
		if (steps.size() > step) {
			for (Holder<PlacedFeature> placed : steps.get(step)) collect(placed.value(), 1.0F, kinds, 0);
		}
		float total = 0;
		for (Kind kind : kinds) total += kind.perChunk;
		return kinds.isEmpty() ? Mix.NONE : new Mix(List.copyOf(kinds), total);
	}

	/** Walks a placed feature down through random selectors, multiplying in how often each branch is reached. */
	private static void collect(PlacedFeature placed, float rate, List<Kind> out, int depth) {
		if (depth > 8) return;
		rate *= expectedCount(placed.placement());
		if (rate <= 0) return;
		Feature feature = placed.feature().value();
		if (feature instanceof TreeFeature tree) {
			Kind kind = describe(tree, rate);
			if (kind != null) out.add(kind);
		} else if (feature instanceof RandomSelectorFeature selector) {
			float left = 1.0F;
			for (WeightedPlacedFeature option : selector.features()) {
				float chance = left * option.chance();
				collect(option.feature().value(), rate * chance, out, depth + 1);
				left -= chance;
			}
			collect(selector.defaultFeature().value(), rate * Math.max(0, left), out, depth + 1);
		} else {
			// Other selectors: split evenly between whatever they can place.
			List<Holder<Feature>> subs = feature.getSubFeatures().toList();
			for (Holder<Feature> sub : subs) collectFeature(sub.value(), rate / subs.size(), out, depth + 1);
		}
	}

	private static void collectFeature(Feature feature, float rate, List<Kind> out, int depth) {
		collect(new PlacedFeature(Holder.direct(feature), List.of()), rate, out, depth);
	}

	private static float expectedCount(List<PlacementModifier> modifiers) {
		float count = 1.0F;
		for (PlacementModifier modifier : modifiers) {
			switch (modifier) {
				case CountPlacement c -> count *= mean(c.count());
				case RarityFilter r -> count /= Math.max(1, r.chance());
				case NoiseThresholdCountPlacement n -> count *= (n.belowNoise() + n.aboveNoise()) * 0.5F;
				case NoiseBasedCountPlacement n -> count *= (float) Math.max(0, n.noiseOffset() * n.noiseToCountRatio());
				default -> {
				}
			}
		}
		return count;
	}

	private static float mean(IntProvider provider) {
		RandomSource random = RandomSource.create(42);
		int sum = 0;
		for (int i = 0; i < 64; i++) sum += provider.sample(random);
		return sum / 64.0F;
	}

	private static Kind describe(TreeFeature tree, float perChunk) {
		RandomSource random = RandomSource.create(42);
		float height = 0, radius = 0, crown = 0;
		for (int i = 0; i < SAMPLES; i++) {
			int trunk = tree.trunkPlacer().getTreeHeight(random);
			height += trunk;
			radius += tree.foliagePlacer().foliageRadius(random, trunk);
			try {
				crown += tree.foliagePlacer().foliageHeight(random, trunk, tree);
			} catch (RuntimeException e) {
				crown += 3;
			}
		}
		height /= SAMPLES;
		radius /= SAMPLES;
		crown /= SAMPLES;
		BlockState leaves = leaves(tree);
		if (leaves == null || leaves.isAir()) return null;
		// Some placers report a radius or height for the whole foliage volume rather than the crown (poplars report
		// half the tree). Keep a trunk showing under every crown and its width in proportion, as trees grow.
		boolean columnar = tree.foliagePlacer() instanceof PoplarFoliagePlacer;
		float trunkTop = height + 1;
		float crownHeight = Math.clamp(crown, 2.0F, Math.max(2.0F, trunkTop - 2));
		float crownRadius = Math.clamp(radius + 0.5F, 1.0F, Math.max(1.5F, columnar ? 2.0F : trunkTop * 0.45F));
		return new Kind(perChunk, trunkTop, crownRadius, crownHeight, leaves);
	}

	private static BlockState leaves(TreeFeature tree) {
		try {
			return tree.foliageProvider().value().getState(null, RandomSource.create(7), BlockPos.ZERO);
		} catch (RuntimeException e) {
			return Blocks.OAK_LEAVES.defaultBlockState();
		}
	}
}
