package dev.winter.seedlod.api;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import org.jspecify.annotations.Nullable;

/**
 * The average look of the trees a feature grows, for far terrain. There Seed LOD doesn't run tree features: it
 * scatters as many crowns per chunk as the biome's placement modifiers give, each this shape.
 */
@FunctionalInterface
public interface TreeShape<F extends Feature> {
	/**
	 * Called once per configured feature; the answer is cached.
	 *
	 * @param random fixed-seeded, for averaging providers ({@code IntProvider}, {@code BlockStateProvider})
	 * @return null for no canopy (a feature that doesn't show from afar)
	 */
	@Nullable Canopy describe(F feature, RandomSource random);

	/**
	 * @param height blocks from the ground to the top of the crown
	 * @param crownRadius horizontal reach of the crown from the trunk, in blocks
	 * @param crownHeight blocks from the bottom of the crown to its top
	 * @param leaves the block the crown is drawn with
	 */
	record Canopy(float height, float crownRadius, float crownHeight, BlockState leaves) {
	}
}
