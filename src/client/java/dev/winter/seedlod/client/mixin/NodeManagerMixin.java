package dev.winter.seedlod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Our coarse sections are leaves above level 0, which Voxy only expects at the top level.
 *
 * <p>When the renderer wants more detail than such a leaf has, it asks for its children. Voxy turns the request
 * down and clears the leaf's "requested" flag, so the same leaf asks again next frame, for as long as it's in view.
 * The renderer takes at most 50 requests a frame, coarsest level first, so a few hundred of our leaves in view use up
 * every slot, and nodes that do have children waiting never get loaded: the view stays coarse until the whole fill
 * is done. Voxy already parks a childless top-level leaf instead: one request stays open, and it's served when
 * children appear. That's what we want for ours too.
 */
@Mixin(value = NodeManager.class, remap = false)
abstract class NodeManagerMixin {
	@WrapOperation(
		method = "makeLeafChildRequest",
		at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;contains(J)Z")
	)
	private boolean seedlod$parkChildlessLeaf(LongOpenHashSet topLevelNodes, long pos, Operation<Boolean> original) {
		return true;
	}

	/** A request finishing with a coarse leaf as a child warns too; in play that's thousands of lines a minute. */
	@WrapOperation(
		method = "finishRequest(ILme/cortex/voxy/client/core/rendering/hierachical/NodeChildRequest;)V",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/common/Logger;warn([Ljava/lang/Object;)V")
	)
	private void seedlod$quietCoarseLeaf(Object[] args, Operation<Void> original) {
	}
}
