package dev.skycraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.client.ClickActivate;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notices a right click Minecraft had no use for (ClickActivate). startUseItem tries each hand on
 * the entity or block under the crosshair and then the held item, and returns the moment one of
 * them takes the click (or refuses it, or a mining swing, busy hands or the world border stop it).
 * Only when none did does it run off the end of its hand loop, its last return (TAIL).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftUseMixin {
	@Unique
	private boolean skycraft$useUnused;

	@Inject(method = "startUseItem", at = @At("TAIL"))
	private void skycraft$noUse(CallbackInfo ci) {
		this.skycraft$useUnused = true;
	}

	// handleKeybinds calls startUseItem for each press (keyUse.consumeClick loop, ordinal 0) and again
	// every 4 ticks while the key is held (ordinal 1). Only the press may activate anything.
	@WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;startUseItem()V", ordinal = 0))
	private void skycraft$usePress(Minecraft minecraft, Operation<Void> original) {
		this.skycraft$useUnused = false;
		original.call(minecraft);
		if (this.skycraft$useUnused) {
			this.skycraft$useUnused = false;
			ClickActivate.onUnusedPress(minecraft);
		}
	}
}
