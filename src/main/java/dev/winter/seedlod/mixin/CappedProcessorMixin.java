package dev.winter.seedlod.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.winter.seedlod.gen.RealTrees;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.CappedProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** {@code CappedProcessor} only asks {@code level.getLevel()} for the world seed; our stage has the seed without a live ServerLevel. */
@Mixin(CappedProcessor.class)
abstract class CappedProcessorMixin {
	@WrapOperation(method = "finalizeProcessing", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getSeed()J"))
	private long seedlod$stageSeed(ServerLevel instance, Operation<Long> original,
		ServerLevelAccessor level, BlockPos pos, BlockPos pivot,
		List<StructureTemplate.StructureBlockInfo> originalBlocks, List<StructureTemplate.StructureBlockInfo> currentBlocks,
		StructurePlaceSettings settings) {
		if (level instanceof RealTrees.Staged && level instanceof WorldGenLevel wgl) return wgl.getSeed();
		return original.call(instance);
	}
}
