package dev.winter.seedlod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.winter.seedlod.client.LodTree;
import java.util.concurrent.locks.ReentrantLock;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Real chunk data entering Voxy: recorded as real so we never write over it, and our coarse sections on its path are
 * split first, under the same lock we write with.
 */
@Mixin(value = WorldUpdater.class, remap = false)
abstract class WorldUpdaterMixin {
	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

	@WrapMethod(method = "insertUpdate")
	private static void seedlod$splitCoarseFirst(WorldEngine into, VoxelizedSection section, Operation<Void> original) {
		int x = section.x, y = section.y, z = section.z;
		withColumn(x, z, () -> {
			LodTree.real(into).add(x, z);
			if (into.isLive()) LodTree.prepareRealInsert(into, x, y, z);
			original.call(into, section);
		});
		// Voxy meshes a level 0 section against its level 0 neighbours. Give the real section coarse neighbours at
		// that level too, or its sides are drawn all the way down where the real area ends. Each neighbour is done
		// under its own column's lock, never two at once.
		for (int[] side : SIDES) {
			int nx = x + side[0], nz = z + side[1];
			withColumn(nx, nz, () -> {
				if (into.isLive()) LodTree.prepareRealInsert(into, nx, y, nz);
			});
		}
	}

	private static void withColumn(int sectionX, int sectionZ, Runnable action) {
		ReentrantLock lock = LodTree.lock(sectionX >> 5, sectionZ >> 5);
		lock.lock();
		try {
			action.run();
		} finally {
			lock.unlock();
		}
	}
}
