package dev.winter.seedlod.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Structure pieces that spawn mobs via {@code EntityType.create(level.getLevel(), ...)} get null cleanly when our stage has no live level. */
@Mixin(EntityType.class)
abstract class EntityTypeMixin<T extends Entity> {
	@Inject(method = "create(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/EntitySpawnReason;)Lnet/minecraft/world/entity/Entity;",
		at = @At("HEAD"), cancellable = true)
	private void seedlod$skipNullLevel(Level level, EntitySpawnReason reason, CallbackInfoReturnable<T> cir) {
		if (level == null) cir.setReturnValue(null);
	}
}
