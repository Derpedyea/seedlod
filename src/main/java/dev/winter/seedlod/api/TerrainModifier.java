package dev.winter.seedlod.api;

import org.jspecify.annotations.Nullable;

/**
 * Edits Seed LOD's terrain the way a mod edits real chunks outside the density functions and surface rules (rivers
 * carved after noise, terrain patched in a chunk status mixin). Runs on every generated area after the surface
 * rules, before trees, so trees grow on the edited ground.
 *
 * <p>An edit at a position must depend only on that position and the world, never on the area's bounds: areas
 * overlap, and the same ground is generated again at each level of detail.
 */
@FunctionalInterface
public interface TerrainModifier {
	void modify(TerrainArea area);

	/**
	 * Makes a modifier for one dimension. Called once per generator thread and dimension, so the modifier it returns
	 * is only ever used by one thread at a time and can keep caches (density samplers, scratch arrays) unlocked.
	 */
	@FunctionalInterface
	interface Factory {
		/** @return null to leave this dimension alone */
		@Nullable TerrainModifier create(WorldgenContext world);
	}
}
