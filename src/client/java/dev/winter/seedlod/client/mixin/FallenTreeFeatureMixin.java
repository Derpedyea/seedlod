package dev.winter.seedlod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.winter.seedlod.gen.RealTrees;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Our trees' level has no chunks to mark for post-processing, and its blocks never update anyway. */
@Mixin(FallenTreeFeature.class)
abstract class FallenTreeFeatureMixin {
	@WrapOperation(method = "placeLogBlock", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/levelgen/feature/FallenTreeFeature;markAboveForPostProcessing(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos;)V"))
	private void seedlod$noChunks(FallenTreeFeature feature, WorldGenLevel level, BlockPos pos, Operation<Void> original) {
		if (!(level instanceof RealTrees.Staged)) original.call(feature, level, pos);
	}
}
