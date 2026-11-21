package dev.winter.seedlod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.winter.seedlod.gen.RealTrees;
import java.util.Set;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Our structure stage has no chunks to mark for post-processing. */
@Mixin(StructurePiece.class)
abstract class StructurePieceMixin {
	@WrapOperation(method = "placeBlock", at = @At(value = "INVOKE", target = "Ljava/util/Set;contains(Ljava/lang/Object;)Z"))
	private boolean seedlod$noPostProcess(Set<?> set, Object block, Operation<Boolean> original,
		WorldGenLevel level, BlockState state, int x, int y, int z, BoundingBox box) {
		return !(level instanceof RealTrees.Staged) && original.call(set, block);
	}
}
