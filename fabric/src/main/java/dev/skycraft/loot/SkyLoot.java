package dev.skycraft.loot;

import dev.skycraft.SkyCraft;
import dev.skycraft.config.Config;
import dev.skycraft.config.Features;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Skyrim creatures the player kills drop Minecraft items (meat, leather, bones) at their feet.
 *
 * <p>Skyrim already reports every nearby actor's name and dead flag each frame. A drop happens
 * when an actor seen alive turns dead within {@link #HIT_WINDOW_TICKS} of the player's last hit on
 * it, so corpses walked up to and kills by others drop nothing. Each actor drops at most once,
 * except creatures SkyCraft spawned itself: Skyrim reuses the form ids of deleted ones, so a
 * spawned creature seen alive is always a new one.
 * What drops comes from {@code config/skycraft-loot.txt} (see {@link LootRules}). Server thread only.
 */
public final class SkyLoot {
	/** 10 seconds: a kill by bleeding, fire or a follow-up arrow still counts as the player's. */
	public static final int HIT_WINDOW_TICKS = 200;
	private static final String FILE = "skycraft-loot.txt";

	private static final Map<Integer, Boolean> WAS_DEAD = new HashMap<>();
	private static final Map<Integer, Integer> LAST_HIT = new HashMap<>();
	private static final Set<Integer> LOOTED = new HashSet<>();
	/** Form ids in the latest actor table; reused every tick. */
	private static final Set<Integer> SEEN = new HashSet<>();
	private static final Random RANDOM = new Random();
	private static LootRules rules;

	private SkyLoot() {
	}

	/** The player landed a hit on this actor on this server tick. */
	public static void recordHit(int formId, int tick) {
		LAST_HIT.put(formId, tick);
	}

	public static void reset() {
		WAS_DEAD.clear();
		LAST_HIT.clear();
	}

	/** Called with each fresh read of Skyrim's actor table. */
	public static void onActors(ServerLevel level, List<SkyLink.Actor> actors, int tick) {
		SEEN.clear();
		for (SkyLink.Actor a : actors) {
			Integer id = a.formId();
			SEEN.add(id);
			Boolean before = WAS_DEAD.put(id, a.dead());
			if (!a.dead() || !Boolean.FALSE.equals(before) || LOOTED.contains(id)) {
				continue;
			}
			Integer hit = LAST_HIT.get(id);
			if (hit == null || tick - hit > HIT_WINDOW_TICKS) {
				SkyCraft.LOG.info("SkyCraft-loot: {} died without a recent hit from you, no drop", a.name());
				continue;
			}
			if ((a.flags() & Proto.ACTOR_SPAWNED) == 0) {
				LOOTED.add(id);
			}
			if (Features.on(Features.Feature.KILL_XP)) {
				// Straight into the killer's XP bar: orbs dropped at a Skyrim corpse can land under the terrain or
				// out of reach, and SkyCraft doesn't draw them well.
				int xp = KillXp.forLevel(a.level());
				net.minecraft.world.entity.player.Player killer = level.getNearestPlayer(a.x(), a.y(), a.z(), 96.0, false);
				if (killer != null) {
					killer.giveExperiencePoints(xp);
					level.playSound(null, killer.getX(), killer.getY(), killer.getZ(), net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP,
						net.minecraft.sounds.SoundSource.PLAYERS, 0.2F, 0.6F + level.getRandom().nextFloat() * 0.6F);
				}
				SkyCraft.LOG.info("SkyCraft-loot: killed {} (level {}), {} XP{}", a.name(), a.level(), xp, killer != null ? " to " + killer.getName().getString() : "");
			}
			if (Features.on(Features.Feature.KILL_DROPS)) {
				drop(level, a);
			}
		}
		// An actor the player just hit is remembered alive while it's briefly out of the table (out
		// of range, Skyrim's table emptied for a frame), so dying there still counts.
		WAS_DEAD.keySet().removeIf(id -> !SEEN.contains(id) && !LAST_HIT.containsKey(id));
		if (!LAST_HIT.isEmpty() && tick % 20 == 0) {
			for (Iterator<Integer> it = LAST_HIT.values().iterator(); it.hasNext(); ) {
				if (tick - it.next() > HIT_WINDOW_TICKS * 3) {
					it.remove();
				}
			}
		}
	}

	private static void drop(ServerLevel level, SkyLink.Actor a) {
		LootRules.Rule rule = rules().match(a.name());
		if (rule == null) {
			SkyCraft.LOG.info("SkyCraft-loot: killed {}, no drop rule for that name", a.name());
			return;
		}
		List<String> dropped = new ArrayList<>();
		for (LootRules.Drop d : LootRules.roll(rule, RANDOM)) {
			Identifier key = Identifier.tryParse(d.item());
			Item item = key != null ? BuiltInRegistries.ITEM.getValue(key) : null;
			if (item == null || item == Items.AIR) {
				SkyCraft.LOG.warn("SkyCraft-loot: unknown item '{}' in {}", d.item(), FILE);
				continue;
			}
			ItemEntity entity = new ItemEntity(level, a.x(), a.y() + 0.5, a.z(), new ItemStack(item, d.min()));
			entity.setDefaultPickUpDelay();
			level.addFreshEntity(entity);
			dropped.add(d.min() + " " + d.item());
		}
		SkyCraft.LOG.info("SkyCraft-loot: killed {}, dropped {}", a.name(), dropped.isEmpty() ? "nothing this time" : String.join(", ", dropped));
	}

	private static LootRules rules() {
		if (rules == null) {
			List<String> problems = new ArrayList<>();
			rules = LootRules.parse(Config.read(FILE, LootRules.DEFAULT_TEXT), problems);
			problems.forEach(p -> SkyCraft.LOG.warn("SkyCraft-loot: {} {}", FILE, p));
			SkyCraft.LOG.info("SkyCraft-loot: {} drop rules loaded", rules.rules().size());
		}
		return rules;
	}
}
