package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The "Loading terrain" screen waits for the player's chunk section to be compiled for
 * rendering. We never render Minecraft's level while linked, so don't wait for it. In another
 * dimension (MinecraftView) Minecraft does draw its own world: wait as usual there.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForPlayerChunk")
public abstract class WaitingForPlayerChunkMixin {
	@Shadow
	@Final
	private ClientLevel level;

	@Inject(method = "isReady", at = @At("HEAD"), cancellable = true)
	private void skycraft$ready(CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.linked() && dev.skycraft.SkyDimension.inMirror(this.level)) {
			cir.setReturnValue(true);
		}
	}
}
