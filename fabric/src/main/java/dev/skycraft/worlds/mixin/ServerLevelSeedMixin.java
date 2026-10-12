package dev.skycraft.worlds.mixin;

import dev.skycraft.worlds.Worlds;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pins a dimension's seed ({@link Worlds#pinnedSeed}): Tamriel Wilds and Home generate the same land in
 * every world. ServerLevel.getSeed feeds the dimension's noise (ChunkMap), structure placement, feature
 * decoration and carvers, so one hook covers all of them.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSeedMixin {
	@Inject(method = "getSeed", at = @At("HEAD"), cancellable = true)
	private void skycraft$pinnedSeed(CallbackInfoReturnable<Long> cir) {
		Optional<Long> pinned = Worlds.pinnedSeed(((ServerLevel) (Object) this).dimension());
		pinned.ifPresent(cir::setReturnValue);
	}
}
