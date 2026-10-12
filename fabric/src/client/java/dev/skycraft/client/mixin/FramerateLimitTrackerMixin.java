package dev.skycraft.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.skycraft.client.SkyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SkyClient.paceFrame() locks us to Skyrim's frame rate; don't let MC throttle on its own. While
 * Skyrim is paused and Minecraft draws a whole world of its own (MinecraftView), nothing paces it:
 * a steady 60 then instead of as fast as it can.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void skycraft$unlimited(CallbackInfoReturnable<Integer> cir) {
		if (SkyClient.linked()) {
			cir.setReturnValue(dev.skycraft.client.MinecraftView.frameLimit());
		}
	}
}
