package dev.winter.seedlod.gen;

import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * Vanilla's {@code freeze_top_layer}, the last decoration step: in cold biomes the topmost water turns to ice, and
 * ground that would hold one gets a snow layer (snowy grass under it). Snowy plains, frozen rivers and oceans and
 * snowy taiga tree tops get theirs this way, not from the surface rules. Runs after trees, as vanilla does.
 */
final class Frost {
	private static final BlockState ICE = Blocks.ICE.defaultBlockState();
	private static final BlockState SNOW = Blocks.SNOW.defaultBlockState();
	private static final Predicate<BlockState> MOTION_BLOCKING = Heightmap.Types.MOTION_BLOCKING.isOpaque();

	private final WorldgenView view;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	/** Whether a snow layer can sit on a state, per state: the shape check isn't free. */
	private final Reference2BooleanOpenHashMap<BlockState> holdsSnow = new Reference2BooleanOpenHashMap<>();

	Frost(WorldgenView view) {
		this.view = view;
	}

	void apply(Tile tile) {
		int step = 1 << tile.lvl, half = step >> 1;
		for (int z = 0; z < tile.size; z++)
			for (int x = 0; x < tile.size; x++) {
				int column = x + z * tile.size;
				Holder<Biome> holder = tile.biomes[column];
				if (holder == null || !this.view.features.freezes(holder)) continue;
				int base = column * tile.voxelsY;
				int y = tile.voxelsY - 1;
				while (y >= 0 && (tile.states[base + y] == null || !MOTION_BLOCKING.test(tile.states[base + y]))) y--;
				if (y < 0) continue;
				Biome biome = holder.value();
				BlockState top = tile.states[base + y];
				// Vanilla's heightmap height: the bottom of the voxel above the top one.
				int height = (tile.voxelMinY + y + 1) * step;
				this.pos.set((tile.voxelX + x) * step + half, height - 1, (tile.voxelZ + z) * step + half);
				// Biome.shouldFreeze without the neighbour check, as the feature asks it.
				if (top.getBlock() instanceof LiquidBlock && top.getFluidState().is(Fluids.WATER) && !biome.warmEnoughToRain(this.pos, this.view.seaLevel)) {
					tile.states[base + y] = top = ICE;
					tile.light[base + y] = 15;
				}
				// Biome.shouldSnow: open air above and snow falling there.
				if (y + 1 >= tile.voxelsY || tile.states[base + y + 1] != null) continue;
				this.pos.setY(height);
				if (biome.getPrecipitationAt(this.pos, this.view.seaLevel) != Biome.Precipitation.SNOW || !this.holdsSnow(top)) continue;
				tile.states[base + y + 1] = SNOW;
				tile.light[base + y + 1] = 15;
				if (top.hasProperty(SnowyBlock.SNOWY)) tile.states[base + y] = top.setValue(SnowyBlock.SNOWY, true);
			}
	}

	/** SnowLayerBlock.canSurvive on the block below. */
	private boolean holdsSnow(BlockState below) {
		if (this.holdsSnow.containsKey(below)) return this.holdsSnow.getBoolean(below);
		boolean holds = !below.is(BlockTags.CANNOT_SUPPORT_SNOW_LAYER) && (below.is(BlockTags.SUPPORT_OVERRIDE_SNOW_LAYER)
			|| Block.isFaceFull(below.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), Direction.UP)
			|| below.is(Blocks.SNOW) && below.getValue(SnowLayerBlock.LAYERS) == SnowLayerBlock.MAX_HEIGHT);
		this.holdsSnow.put(below, holds);
		return holds;
	}
}
