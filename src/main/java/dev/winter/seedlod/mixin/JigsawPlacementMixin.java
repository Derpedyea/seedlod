package dev.winter.seedlod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.winter.seedlod.gen.Hooks;
import dev.winter.seedlod.gen.JigsawBounds;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Uses exact O(N) AABB collision bounds during Seed LOD Jigsaw generation instead of vanilla's O(N³) VoxelShape. */
@Mixin(JigsawPlacement.class)
abstract class JigsawPlacementMixin {
	@WrapOperation(method = "lambda$addPieces$2", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/phys/shapes/Shapes;join(Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/world/phys/shapes/BooleanOp;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
	private static VoxelShape seedlod$fastBounds(VoxelShape outer, VoxelShape start, BooleanOp op, Operation<VoxelShape> original) {
		if (Hooks.current() != null && op == BooleanOp.ONLY_FIRST) {
			JigsawBounds bounds = new JigsawBounds(outer.bounds());
			bounds.subtract(start.bounds());
			return bounds;
		}
		return original.call(outer, start, op);
	}
}
