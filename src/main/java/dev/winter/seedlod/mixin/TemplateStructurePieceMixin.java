package dev.winter.seedlod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.winter.seedlod.gen.RealTrees;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Data markers in template pieces spawn mobs or populate chest loot, neither of which applies to LOD terrain. */
@Mixin(TemplateStructurePiece.class)
abstract class TemplateStructurePieceMixin {
	@WrapOperation(method = "postProcess", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/levelgen/structure/TemplateStructurePiece;handleDataMarker(Ljava/lang/String;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;)V"))
	private void seedlod$skipDataMarkers(TemplateStructurePiece self, String marker, BlockPos pos, ServerLevelAccessor level,
		RandomSource random, BoundingBox box, Operation<Void> original) {
		if (!(level instanceof RealTrees.Staged)) original.call(self, marker, pos, level, random, box);
	}
}
