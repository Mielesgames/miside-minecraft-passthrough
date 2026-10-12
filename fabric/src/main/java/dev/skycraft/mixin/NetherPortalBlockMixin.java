package dev.skycraft.mixin;

import dev.skycraft.portal.SkyPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No vanilla Nether travel anywhere: Entity.handlePortal asks the portal for its destination once
 * Minecraft's usual wait is over (PortalProcessor), and a null destination means no teleport. The portal
 * network (SkyPortals.reach) takes the moment instead, in the Skyrim mirror and in every other dimension
 * (a portal there that isn't part of the network leads nowhere, rather than into the mirror at scaled
 * coordinates, which would put Skyrim's player somewhere random).
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {
	@Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
	private void skycraft$portalNetwork(ServerLevel level, Entity entity, BlockPos entry, CallbackInfoReturnable<TeleportTransition> cir) {
		if (SkyPortals.intercepts(level)) {
			SkyPortals.reach(level, entity, entry);
			cir.setReturnValue(null);
		}
	}
}
