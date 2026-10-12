package dev.skycraft.worlds.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.skycraft.worlds.Worlds;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets structures start in the Nether, Tamriel Wilds and Home even though the SkyCraft world was created
 * with structures off ({@link Worlds#forcesStructures}). The mirror overworld keeps the world's setting.
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
	@ModifyExpressionValue(
		method = "generateStructureStarts",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/WorldOptions;generateStructures()Z")
	)
	private static boolean skycraft$structuresHere(boolean original, @Local(argsOnly = true) WorldGenContext context) {
		return original || Worlds.forcesStructures(context.level().dimension());
	}
}
