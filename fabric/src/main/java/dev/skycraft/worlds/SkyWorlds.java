package dev.skycraft.worlds;

import dev.skycraft.SkyCraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Registers what the worlds' data needs from code: Home's chunk generator type. The dimensions, biomes and
 * structures are data (fabric/src/main/resources/data, written by tools/gen_worlds_data.py); the seed pin
 * and the structure switch per dimension are mixins in {@link dev.skycraft.worlds.mixin}.
 */
public final class SkyWorlds {
	private SkyWorlds() {}

	public static void init() {
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "classic"), HomeChunkGenerator.CODEC);
		SkyCraft.LOG.info("SkyCraft-worlds: registered Home's generator; worlds: {}, {}, {}",
			Worlds.NETHER.identifier(), Worlds.TAMRIEL_WILDS.identifier(), Worlds.HOME.identifier());
	}
}
