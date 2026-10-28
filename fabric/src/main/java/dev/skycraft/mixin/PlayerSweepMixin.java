package dev.skycraft.mixin;

import dev.skycraft.combat.SkyrimActorEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A sword's sweep that started on a host character's stand-in: the host hears it was a sweep. */
@Mixin(Player.class)
public abstract class PlayerSweepMixin {
	@org.spongepowered.asm.mixin.Unique
	private static long skycraft$sweepLogged;

	/**
	 * A sweep as the player's own client decides it (the server's attack may not get that far for
	 * a stand-in): the host is told at once, and takes the blow that follows as a sweep.
	 */
	@Inject(method = "isSweepAttack", at = @At("RETURN"))
	private void skycraft$whySweep(boolean crit, boolean sprintKnockback, boolean charged, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		Player self = (Player) (Object) this;
		if (cir.getReturnValueZ() && self.level().isClientSide() && dev.skycraft.link.SkyLink.active()) {
			dev.skycraft.link.SkyLink.pushEvent(dev.skycraft.link.Proto.EV_SWEEP, 0, 0, 0, 0, 0, 0);
			// The sweep's arc and its swish, where Minecraft puts them (the server, which normally
			// sends them, doesn't get that far for a host character's stand-in).
			double yaw = Math.toRadians(self.getYRot());
			double x = self.getX() - Math.sin(yaw), z = self.getZ() + Math.cos(yaw);
			self.level().addParticle(net.minecraft.core.particles.ParticleTypes.SWEEP_ATTACK, x, self.getY(0.5), z, 0.0, 0.0, 0.0);
			self.level().playLocalSound(self.getX(), self.getY(), self.getZ(), net.minecraft.sounds.SoundEvents.PLAYER_ATTACK_SWEEP,
				self.getSoundSource(), 1.0F, 1.0F, false);
		}
		long now = System.currentTimeMillis();
		if (now - skycraft$sweepLogged > 1000) {
			skycraft$sweepLogged = now;
			dev.skycraft.SkyCraft.LOG.info("MiSideCraft: sweep {} (crit {}, sprint knockback {}, charged {}, on ground {}, sword {})", cir.getReturnValue(), crit,
				sprintKnockback, charged, self.onGround(), self.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS));
		}
	}

	@Inject(method = "doSweepAttack", at = @At("HEAD"))
	private void skycraft$sweep(Entity target, float damage, DamageSource source, float strength, CallbackInfo ci) {
		if (target instanceof SkyrimActorEntity proxy) {
			proxy.markSweep();
		}
	}
}
