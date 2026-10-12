package dev.skycraft.mixin;

import dev.skycraft.portal.SkyPortals;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Obsidian frames light in Tamriel Wilds and Home too. Vanilla lights a Nether portal only in the overworld
 * and the Nether (BaseFireBlock.inPortalDimension, read by onPlace and canBePlacedAt); the network needs lit
 * portals in every world, for the Seamstone (any lit portal) and a way out of Home. They lead nowhere
 * unless the network says so (NetherPortalBlockMixin).
 */
@Mixin(BaseFireBlock.class)
public abstract class BaseFireBlockMixin {
	@Inject(method = "inPortalDimension", at = @At("RETURN"), cancellable = true)
	private static void skycraft$portalsEverywhere(Level level, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && SkyPortals.lightsPortals(level)) {
			cir.setReturnValue(true);
		}
	}
}
