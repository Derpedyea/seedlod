package dev.winter.seedlod.client.mixin;

import dev.winter.seedlod.client.FrameStats;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The game reports every frame's duration here; the autopilot reads them to measure stutter. */
@Mixin(DebugScreenOverlay.class)
abstract class DebugOverlayMixin {
	@Inject(method = "logFrameDuration", at = @At("HEAD"))
	private void seedlod$frame(long nanos, CallbackInfo ci) {
		FrameStats.record(nanos);
	}
}
