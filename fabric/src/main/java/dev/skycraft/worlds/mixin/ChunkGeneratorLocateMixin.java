package dev.skycraft.worlds.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.skycraft.worlds.Worlds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lets /locate structure (and explorer maps) find the structures {@link ChunkStatusTasksMixin} allows. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorLocateMixin {
	@ModifyExpressionValue(
		method = "findNearestMapStructure",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/WorldOptions;generateStructures()Z")
	)
	private boolean skycraft$structuresHere(boolean original, @Local(argsOnly = true) ServerLevel level) {
		return original || Worlds.forcesStructures(level.dimension());
	}
}
