package dev.winter.seedlod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.winter.seedlod.client.LodTree;
import java.util.concurrent.locks.ReentrantLock;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Real chunk data entering Voxy: recorded as real so we never write over it, our coarse sections on its path are
 * split first under the same lock we write with, and any higher LOD levels Voxy skipped on an unchanged lower level
 * are still updated so our independently generated approximations are replaced by Voxy's real mips.
 */
@Mixin(value = WorldUpdater.class, remap = false)
abstract class WorldUpdaterMixin {
	@Unique
	private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
	@Unique
	private static final ThreadLocal<int[]> LAST_LEVEL = ThreadLocal.withInitial(() -> new int[]{Integer.MIN_VALUE});

	@WrapMethod(method = "insertUpdate")
	private static void seedlod$splitCoarseFirst(WorldEngine into, VoxelizedSection section, Operation<Void> original) {
		int x = section.x, y = section.y, z = section.z;
		withColumn(x, z, () -> {
			LodTree.real(into).add(x, z);
			if (into.isLive()) LodTree.prepareRealInsert(into, x, y, z);
			int[] last = LAST_LEVEL.get();
			last[0] = -1;
			int reached;
			try {
				original.call(into, section);
				reached = last[0];
			} finally {
				last[0] = Integer.MIN_VALUE;
			}
			if (reached >= 0 && reached < WorldEngine.MAX_LOD_LAYER && into.isLive()) {
				LodTree.syncUpperLevels(into, section, reached + 1);
			}
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

	@Inject(method = "insertSectionLvlIntoWorld", at = @At("HEAD"))
	private static void seedlod$trackLevel(VoxelizedSection section, WorldSection worldSection, CallbackInfoReturnable<Long> cir) {
		int[] last = LAST_LEVEL.get();
		if (last[0] >= -1) last[0] = Math.max(last[0], worldSection.lvl);
	}

	@Unique
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
