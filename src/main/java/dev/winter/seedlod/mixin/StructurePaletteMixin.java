package dev.winter.seedlod.mixin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code StructureTemplate.Palette.cache} is a plain {@link java.util.HashMap} populated lazily by {@code blocks(Block)}
 * and {@code jigsaws()}; concurrent Seed LOD structure workers hit it simultaneously and would throw
 * {@code ConcurrentModificationException}. Swap it for a {@link ConcurrentHashMap} at construction.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate$Palette")
abstract class StructurePaletteMixin {
	@Shadow
	@Final
	@Mutable
	private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void seedlod$concurrentCache(List<StructureTemplate.StructureBlockInfo> blocks, CallbackInfo ci) {
		this.cache = new ConcurrentHashMap<>();
	}
}
