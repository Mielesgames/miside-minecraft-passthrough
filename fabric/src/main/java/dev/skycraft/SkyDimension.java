package dev.skycraft;

import dev.skycraft.config.Features;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.dimension.DimensionType;
import org.jspecify.annotations.Nullable;

/**
 * Which dimension mirrors Skyrim. Only the mirror (the overworld, dimension type skycraft:mirror) is
 * shaped like Skyrim's world: Skyrim's collision, water, dug ground, NPC stand-ins, loot, time and
 * the player's position all belong to it. Everywhere else (the Nether, Tamriel Wilds, Home, ...) is a
 * plain Minecraft dimension: Minecraft draws it itself and Skyrim waits (client MinecraftView, Skyrim's
 * View.cpp). Every Skyrim-coupled system asks here before it touches a level. Any thread.
 *
 * <p>With the {@code minecraft_view} switch off every level counts as the mirror, as before.
 */
public final class SkyDimension {
	/** The mirror's dimension type (data/skycraft/dimension_type/mirror.json). */
	public static final ResourceKey<DimensionType> MIRROR_TYPE = ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "mirror"));

	/**
	 * Where the local player is (set by the client every frame): the answer for getters that can't say
	 * which level they belong to.
	 */
	public static volatile boolean localInMirror = true;

	private static volatile int viewSwitch = -1; // -1 not read yet, 0 off, 1 on

	private SkyDimension() {
	}

	/** The minecraft_view switch (read once). */
	public static boolean viewEnabled() {
		int v = viewSwitch;
		if (v < 0) {
			v = Features.on(Features.Feature.MINECRAFT_VIEW) ? 1 : 0;
			viewSwitch = v;
		}
		return v == 1;
	}

	/** True if this level is the Skyrim mirror (or minecraft_view is off, or there's no level to ask). */
	public static boolean inMirror(@Nullable Level level) {
		return level == null || !viewEnabled() || level.dimensionTypeRegistration().is(MIRROR_TYPE);
	}

	/** The same for an entity's level. */
	public static boolean inMirror(@Nullable Entity entity) {
		return entity == null || inMirror(entity.level());
	}

	/**
	 * The same for a block or collision query, which may not come from a Level itself (a worldgen
	 * region, a mob's path finding region): its level, the entity asking's, or else where the local
	 * player is.
	 */
	public static boolean inMirror(@Nullable BlockGetter getter, @Nullable Entity asking) {
		if (getter instanceof Level level) {
			return inMirror(level);
		}
		if (getter instanceof ServerLevelAccessor region) {
			return inMirror(region.getLevel());
		}
		if (asking != null) {
			return inMirror(asking.level());
		}
		return !viewEnabled() || localInMirror;
	}

	/** {@link #inMirror(BlockGetter, Entity)} with no entity. */
	public static boolean inMirror(@Nullable BlockGetter getter) {
		return inMirror(getter, null);
	}
}
