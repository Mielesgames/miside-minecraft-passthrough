package dev.skycraft.worlds;

import dev.skycraft.SkyCraft;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The Sygmension's worlds as Minecraft sees them (docs/LORE.md): the dimension keys, the seeds the lore
 * pins, and where a traveller arrives in each. No side effects; {@link WorldArrivals} turns an
 * {@link Arrival} into a standing spot in a loaded level.
 *
 * The dimensions themselves are data in the mod's own data pack (data/minecraft/dimension/the_nether.json,
 * data/skycraft/dimension/*.json), so they appear in existing worlds too.
 */
public final class Worlds {
	/** The mirror world: Skyrim's shape, the Kalpa side (the world preset's overworld). */
	public static final ResourceKey<Level> MIRROR = Level.OVERWORLD;
	/** Where the Seed family borders Oblivion. Vanilla Nether, added to the mirror world by the mod's data. */
	public static final ResourceKey<Level> NETHER = Level.NETHER;
	/** One step from Skyrim on the Seed side, where the Dwemer went. */
	public static final ResourceKey<Level> TAMRIEL_WILDS = key("tamriel_wilds");
	/** Steve's old seed, ending at the Far Lands. */
	public static final ResourceKey<Level> HOME = key("home");

	/** Tamriel Wilds' lore seed: 4E 201, the year Skyrim's story begins. Applied over the world seed. */
	public static final long TAMRIEL_WILDS_SEED = 4201L;
	/** Home's seed (its terrain reads {@link HomeTerrain#SEED} directly; trees and ores read this one). */
	public static final long HOME_SEED = HomeTerrain.SEED;

	/** Dimensions whose structures generate even when the world was created with structures off. */
	private static final Set<ResourceKey<Level>> STRUCTURES_ALWAYS = Set.of(NETHER, TAMRIEL_WILDS, HOME);

	/** How an arrival's y is found. */
	public enum Placement {
		/** Use y as given. */
		EXACT,
		/** Stand on the topmost solid ground of the column at x, z (y is ignored). */
		SURFACE,
		/** Search down, then up, from y for the nearest two block gap over solid ground (for roofed worlds). */
		CAVERN,
	}

	/** Where a traveller arrives: block position (x, z centred), facing (Minecraft yaw, pitch), how to settle y. */
	public record Arrival(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch, Placement placement) {}

	/**
	 * Tamriel Wilds: Eastmarch lowland a dozen blocks south of the Dwemer tower nearest the origin (centre
	 * 91, 293 with the lore seed), facing north at it. The tower's spot follows from the seed and the
	 * dwemer_tower structure set; check it again with /locate if either changes.
	 */
	public static final Arrival TAMRIEL_WILDS_ARRIVAL = new Arrival(TAMRIEL_WILDS, 91.5, 0.0, 306.5, 180.0f, 0.0f, Placement.SURFACE);
	/** The Nether: near the origin, in the first open space around y 70. */
	public static final Arrival NETHER_ARRIVAL = new Arrival(NETHER, 0.5, 70.0, 0.5, 0.0f, 0.0f, Placement.CAVERN);
	/** Home: in front of Steve's camp on his trail, facing the wall and the crack. */
	public static final Arrival HOME_ARRIVAL = new Arrival(HOME, SteveCamp.ARRIVAL_X, SteveCamp.ARRIVAL_Y, SteveCamp.ARRIVAL_Z,
		SteveCamp.ARRIVAL_YAW, 0.0f, Placement.EXACT);

	private Worlds() {}

	private static ResourceKey<Level> key(String path) {
		return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, path));
	}

	/** The seed a dimension generates from instead of the world's, or empty to use the world's. */
	public static Optional<Long> pinnedSeed(ResourceKey<Level> dimension) {
		if (TAMRIEL_WILDS.equals(dimension)) {
			return Optional.of(TAMRIEL_WILDS_SEED);
		}
		if (HOME.equals(dimension)) {
			return Optional.of(HOME_SEED);
		}
		return Optional.empty();
	}

	/**
	 * True if structures generate in this dimension regardless of the world's "generate structures" option.
	 * The SkyCraft world is created with structures off (the mirror is a void); these worlds need theirs.
	 */
	public static boolean forcesStructures(ResourceKey<Level> dimension) {
		return STRUCTURES_ALWAYS.contains(dimension);
	}

	/** The arrival point for a dimension, if it is one of the Sygmension's. */
	public static Optional<Arrival> arrival(ResourceKey<Level> dimension) {
		if (TAMRIEL_WILDS.equals(dimension)) {
			return Optional.of(TAMRIEL_WILDS_ARRIVAL);
		}
		if (NETHER.equals(dimension)) {
			return Optional.of(NETHER_ARRIVAL);
		}
		if (HOME.equals(dimension)) {
			return Optional.of(HOME_ARRIVAL);
		}
		return Optional.empty();
	}
}
