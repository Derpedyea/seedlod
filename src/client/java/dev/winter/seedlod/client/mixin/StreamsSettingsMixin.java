package dev.winter.seedlod.client.mixin;

import dev.winter.seedlod.gen.RealTrees;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Streams Reflowing keeps features off its rivers by asking the live server level. A client on a server has none, so
 * there its river check is off while our trees are planted; otherwise every tree would fail.
 */
@Pseudo
@Mixin(targets = "dev.streamsreflowing.config.StreamsSettings", remap = false)
abstract class StreamsSettingsMixin {
	@Inject(method = "generateRivers", at = @At("HEAD"), cancellable = true, require = 0)
	private static void seedlod$offWithoutLevel(CallbackInfoReturnable<Boolean> cir) {
		if (RealTrees.NO_LEVEL.get()) cir.setReturnValue(false);
	}
}
