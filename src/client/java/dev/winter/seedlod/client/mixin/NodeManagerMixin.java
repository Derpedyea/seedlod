package dev.winter.seedlod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Our coarse sections are leaves above level 0. Voxy expects leaves only at level 0, so it logs a warning each time
 * it loads one as a child and each frame it would like more detail than one has, then carries on drawing it, which
 * is what we want. In play that is thousands of lines a minute on the node manager's thread.
 */
@Mixin(value = NodeManager.class, remap = false)
abstract class NodeManagerMixin {
	@WrapOperation(
		method = {"makeLeafChildRequest", "finishRequest(ILme/cortex/voxy/client/core/rendering/hierachical/NodeChildRequest;)V"},
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/common/Logger;warn([Ljava/lang/Object;)V")
	)
	private void seedlod$quietCoarseLeaf(Object[] args, Operation<Void> original) {
	}
}
