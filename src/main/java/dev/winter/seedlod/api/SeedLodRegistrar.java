package dev.winter.seedlod.api;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.feature.Feature;

/** What a {@link SeedLodPlugin} can hook into. Only valid during {@link SeedLodPlugin#register}. */
public interface SeedLodRegistrar {
	/**
	 * Adds a pass that edits generated terrain after the surface rules and before trees.
	 *
	 * @param id unique; modifiers run in order of id, so the server and its clients run them the same way
	 * @throws IllegalArgumentException if the id is taken
	 */
	void terrainModifier(Identifier id, TerrainModifier.Factory factory);

	/**
	 * Treats features of this class (and its subclasses) as trees: near terrain runs them like vanilla's trees, and
	 * far terrain draws the canopy {@code shape} describes. A shape registered for a class wins over Seed LOD's own
	 * reading of it.
	 */
	<F extends Feature> void treeFeature(Class<F> type, TreeShape<? super F> shape);
}
