package dev.winter.seedlod.gen;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Hashes of one coarse tile, without trees: shape (solid or not), the blocks the surface rules picked, and biomes.
 * Equal on both sides only if seed, noise, material rules and biome source all agree; three parts say which didn't.
 */
public final class Fingerprint {
	public static long[] of(WorldgenView view) {
		try (TileGen gen = new TileGen(view).withoutTrees()) {
			Tile tile = gen.generate(4, 0, 0);
			long shape = 17, surface = 17, biomes = 17;
			for (BlockState state : tile.states) shape = 31 * shape + (state == null || !state.getFluidState().isEmpty() ? 0 : 1);
			// Only what can be seen: each column's top block, and the floor under water. Deep rock carries ore veins
			// that don't have to agree for the far terrain to.
			for (int z = 0; z < Tile.SIZE; z++)
				for (int x = 0; x < Tile.SIZE; x++) {
					int y = tile.voxelsY - 1;
					while (y >= 0 && tile.states[tile.index(x, y, z)] == null) y--;
					while (y >= 0 && !tile.states[tile.index(x, y, z)].getFluidState().isEmpty()) {
						surface = 31 * surface + tile.states[tile.index(x, y, z)].toString().hashCode();
						y--;
					}
					surface = 31 * surface + (y < 0 ? 0 : tile.states[tile.index(x, y, z)].toString().hashCode());
				}
			for (var biome : tile.biomes) biomes = 31 * biomes + biome.unwrapKey().map(k -> k.identifier().toString()).orElse("?").hashCode();
			if (Boolean.getBoolean("seedlod.debug")) {
				java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
				for (BlockState state : tile.states) if (state != null) counts.merge(state.toString(), 1, Integer::sum);
				dev.winter.seedlod.SeedLod.LOG.info("[fingerprint] {}", counts);
			}
			return new long[] {shape, surface, biomes};
		}
	}
}
