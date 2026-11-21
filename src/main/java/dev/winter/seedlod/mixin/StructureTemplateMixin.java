package dev.winter.seedlod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.winter.seedlod.gen.RealTrees;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * When baking structures for LODs: never spawn entities on worker threads, and skip neighbor shape edge-updates
 * (expensive 6-direction scans per block that don't matter at LOD scale).
 */
@Mixin(StructureTemplate.class)
abstract class StructureTemplateMixin {
	@WrapOperation(method = "placeInWorld", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;updateShapeAtEdge(Lnet/minecraft/world/level/LevelAccessor;ILnet/minecraft/world/phys/shapes/DiscreteVoxelShape;III)V"))
	private void seedlod$skipEdgeUpdates(LevelAccessor level, int flags, DiscreteVoxelShape shape, int x, int y, int z, Operation<Void> original) {
		if (!(level instanceof RealTrees.Staged)) original.call(level, flags, shape, x, y, z);
	}

	@WrapOperation(method = "placeInWorld", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;placeEntities(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Mirror;Lnet/minecraft/world/level/block/Rotation;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;ZLnet/minecraft/util/ProblemReporter;)V"))
	private void seedlod$skipEntities(StructureTemplate self, ServerLevelAccessor level, BlockPos pos, Mirror mirror, Rotation rotation,
		BlockPos pivot, BoundingBox box, boolean finalizeEntities, ProblemReporter reporter, Operation<Void> original) {
		if (!(level instanceof RealTrees.Staged)) original.call(self, level, pos, mirror, rotation, pivot, box, finalizeEntities, reporter);
	}
}
