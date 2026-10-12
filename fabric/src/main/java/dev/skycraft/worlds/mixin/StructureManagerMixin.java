package dev.skycraft.worlds.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.skycraft.worlds.Worlds;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.StructureManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Places the pieces of structures that {@link ChunkStatusTasksMixin} let start (decoration asks this). */
@Mixin(StructureManager.class)
public abstract class StructureManagerMixin {
	@Shadow
	@Final
	private LevelAccessor level;

	@ModifyReturnValue(method = "shouldGenerateStructures", at = @At("RETURN"))
	private boolean skycraft$structuresHere(boolean original) {
		return original || (level instanceof ServerLevelAccessor server && Worlds.forcesStructures(server.getLevel().dimension()));
	}
}
