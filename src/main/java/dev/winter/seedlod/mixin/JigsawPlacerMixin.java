package dev.winter.seedlod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.winter.seedlod.gen.Hooks;
import dev.winter.seedlod.gen.JigsawBounds;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Fast exact AABB collision tests when {@link JigsawBounds} is active on a Seed LOD worker thread. */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement$Placer")
abstract class JigsawPlacerMixin {
	@WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/phys/shapes/Shapes;create(Lnet/minecraft/world/phys/AABB;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		ordinal = 0))
	private VoxelShape seedlod$fastChildBounds(AABB box, Operation<VoxelShape> original) {
		return Hooks.current() != null ? new JigsawBounds(box) : original.call(box);
	}

	@WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
	private boolean seedlod$fastConflict(VoxelShape free, VoxelShape candidate, BooleanOp op, Operation<Boolean> original) {
		if (free instanceof JigsawBounds bounds && op == BooleanOp.ONLY_SECOND) return bounds.conflicts(candidate.bounds());
		return original.call(free, candidate, op);
	}

	@WrapOperation(method = "tryPlacingChildren", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/phys/shapes/Shapes;joinUnoptimized(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
	private VoxelShape seedlod$fastSubtract(VoxelShape free, VoxelShape placed, BooleanOp op, Operation<VoxelShape> original) {
		if (free instanceof JigsawBounds bounds && op == BooleanOp.ONLY_FIRST) {
			bounds.subtract(placed.bounds());
			return bounds;
		}
		return original.call(free, placed, op);
	}
}
