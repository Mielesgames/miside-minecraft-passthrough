package dev.skycraft.companion;

import dev.skycraft.link.Proto;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * Minecraft's taming rules for Skyrim's animals (Companions): what a right click on a Skyrim animal's stand-in
 * does, the odds, what each animal eats and how much it heals. Pure Java, unit tested without Minecraft.
 *
 * <p>The numbers are vanilla 26.3's: {@code Wolf.tryToTame} (one bone in three), {@code Wolf.mobInteract} and
 * {@code Animal.feed} (wolf food heals twice its nutrition, of a tamed wolf's 40 health), {@code
 * AbstractHorse.handleEating} (each horse food's heal and temper) and {@code RunAroundLikeCrazyGoal} (a mount
 * attempt tames when {@code random(100) < temper}, and a failed one adds 5 temper). Nobody can mount a Skyrim horse
 * over and over to tame it here, so each meal stands for one mount attempt.
 *
 * <p>Skyrim decides which actors are dogs, wolves and horses (by race, in skse/src/AnimalKinds.h, where every
 * taming is checked too) and which are already the player's, and says so in the actor table's flags.
 */
public final class CompanionRules {
	private CompanionRules() {
	}

	/** An animal as Minecraft's taming rules see it (Proto.ANIMAL_*). */
	public enum Kind {
		NONE, DOG, WOLF, HORSE;

		/** Dogs and wolves: tamed with bones and fed meat, like a Minecraft wolf. */
		public boolean wolfLike() {
			return this == DOG || this == WOLF;
		}

		public int proto() {
			return switch (this) {
				case DOG -> Proto.ANIMAL_DOG;
				case WOLF -> Proto.ANIMAL_WOLF;
				case HORSE -> Proto.ANIMAL_HORSE;
				case NONE -> Proto.ANIMAL_NONE;
			};
		}
	}

	public static Kind kind(int actorFlags) {
		return switch ((actorFlags & Proto.ACTOR_KIND_MASK) >>> Proto.ACTOR_KIND_SHIFT) {
			case Proto.ANIMAL_DOG -> Kind.DOG;
			case Proto.ANIMAL_WOLF -> Kind.WOLF;
			case Proto.ANIMAL_HORSE -> Kind.HORSE;
			default -> Kind.NONE;
		};
	}

	/** The player's animal (tamed, Skyrim's animal follower, a teammate, an owned horse). */
	public static boolean companion(int actorFlags) {
		return (actorFlags & Proto.ACTOR_COMPANION) != 0;
	}

	/** A companion that isn't following now: sitting, or left behind. */
	public static boolean waiting(int actorFlags) {
		return (actorFlags & Proto.ACTOR_COMPANION_WAITING) != 0;
	}

	/** What a right click on a Skyrim animal's stand-in does. */
	public enum Use {
		/** Nothing here: Minecraft carries on (the held item, then Skyrim's activation: talking, riding a horse). */
		PASS,
		/** A bone for an untamed dog or wolf: one in three tames it. */
		TAME_BONE,
		/** Horse food for an untamed horse: temper, then a mount attempt. */
		FEED_HORSE,
		/** Its food for a companion: heals it (nothing is eaten at full health). */
		HEAL,
		/** An empty hand on a companion: sit (wait) or follow. A horse needs sneaking: without, the click rides it. */
		TOGGLE
	}

	/**
	 * What a right click with this hand does. {@code itemId} is the held item's id ("minecraft:bone"), {@code wolfFood}
	 * whether it's in Minecraft's {@code wolf_food} tag (meat, fish, rabbit stew), {@code sneaking} the player's
	 * secondary use (sneak). Only the main hand's empty click orders, as only one hand should.
	 */
	public static Use use(int actorFlags, boolean mainHand, boolean emptyHand, boolean sneaking, String itemId, boolean wolfFood) {
		if ((actorFlags & Proto.ACTOR_DEAD) != 0) {
			return Use.PASS;
		}
		Kind kind = kind(actorFlags);
		if (!companion(actorFlags)) {
			// A quest's animal (essential: Barbas on his errand) is its quest's; Skyrim wouldn't tame it.
			if (kind == Kind.NONE || emptyHand || (actorFlags & Proto.ACTOR_ESSENTIAL) != 0) {
				return Use.PASS;
			}
			if (kind.wolfLike()) {
				return BONE.equals(itemId) ? Use.TAME_BONE : Use.PASS;
			}
			return horseFood(itemId) != null ? Use.FEED_HORSE : Use.PASS;
		}
		if (emptyHand) {
			if (!mainHand || (kind == Kind.HORSE && !sneaking)) {
				return Use.PASS;
			}
			return Use.TOGGLE;
		}
		if (kind.wolfLike() && wolfFood) {
			return Use.HEAL;
		}
		if (kind == Kind.HORSE && horseFood(itemId) != null) {
			return Use.HEAL;
		}
		return Use.PASS;
	}

	// ---- wolves and dogs --------------------------------------------------------------------------

	public static final String BONE = "minecraft:bone";
	/** Wolf.tryToTame: {@code random.nextInt(3) == 0}. */
	public static final int BONE_ODDS = 3;
	/** A tamed Minecraft wolf's health. */
	public static final float WOLF_MAX_HEALTH = 40.0F;
	/** Animal.feed heals this much per point of the food's nutrition (and this much when it has none). */
	public static final float FEED_HEAL_PER_NUTRITION = 2.0F;
	/**
	 * How long a dog or wolf the player hit won't take a bone (Minecraft's wolves stay angry 20 to 39 seconds after a
	 * hit, and an angry wolf won't be tamed): 30 seconds of server ticks.
	 */
	public static final int ANGRY_TICKS = 600;

	/** {@code random}: a bound to a random number from 0 below it (Minecraft's {@code RandomSource::nextInt}). */
	public static boolean boneTames(IntUnaryOperator random) {
		return random.applyAsInt(BONE_ODDS) == 0;
	}

	/** The player hit it {@code ticksSinceHit} ago (negative: never, as far as Minecraft knows). */
	public static boolean angry(int ticksSinceHit) {
		return ticksSinceHit >= 0 && ticksSinceHit < ANGRY_TICKS;
	}

	/** The share of a dog's or wolf's health a food of this nutrition heals (0 or less: none listed, as Animal.feed). */
	public static float wolfHealShare(int nutrition) {
		float heal = FEED_HEAL_PER_NUTRITION * (nutrition > 0 ? nutrition : 1);
		return clamp01(heal / WOLF_MAX_HEALTH);
	}

	// ---- horses -----------------------------------------------------------------------------------

	/** What a food does for a horse (AbstractHorse.handleEating): health it heals and temper it adds. */
	public record HorseFood(float heal, int temper) {
	}

	/** AbstractHorse.handleEating, for the items in Minecraft's horse_food tag. */
	public static final Map<String, HorseFood> HORSE_FOODS = Map.of(
		"minecraft:wheat", new HorseFood(2.0F, 3),
		"minecraft:sugar", new HorseFood(1.0F, 3),
		"minecraft:hay_block", new HorseFood(20.0F, 0),
		"minecraft:apple", new HorseFood(3.0F, 3),
		"minecraft:carrot", new HorseFood(3.0F, 3),
		"minecraft:golden_carrot", new HorseFood(4.0F, 5),
		"minecraft:golden_apple", new HorseFood(10.0F, 10),
		"minecraft:enchanted_golden_apple", new HorseFood(10.0F, 10)
	);
	/** AbstractHorse.getMaxTemper. */
	public static final int MAX_TEMPER = 100;
	/** RunAroundLikeCrazyGoal: a horse that throws its rider gets this much tamer. */
	public static final int FAILED_MOUNT_TEMPER = 5;
	/** An average Minecraft horse's health (AbstractHorse.generateMaxHealth: 15, plus 0 to 7, plus 0 to 8). */
	public static final float HORSE_HEALTH = 22.5F;

	public static HorseFood horseFood(String itemId) {
		return itemId == null ? null : HORSE_FOODS.get(itemId);
	}

	public static float horseHealShare(HorseFood food) {
		return clamp01(food.heal() / HORSE_HEALTH);
	}

	/**
	 * Feeding an untamed horse. {@code eats}: the food was eaten (no: nothing happens, the item stays); {@code tried}:
	 * it raised the temper, and stood for a mount attempt; {@code tamed}: that attempt tamed it; {@code temper}: the
	 * horse's temper now.
	 */
	public record HorseMeal(boolean eats, boolean tried, boolean tamed, int temper) {
	}

	/**
	 * An untamed horse with this {@code temper} (0 to 100) eats {@code food}: handleEating's temper (a hurt horse also
	 * eats food that only heals, like hay), then a mount attempt as RunAroundLikeCrazyGoal makes it.
	 */
	public static HorseMeal feedUntamedHorse(int temper, HorseFood food, boolean hurt, IntUnaryOperator random) {
		int t = Math.max(0, Math.min(MAX_TEMPER, temper));
		boolean eats = hurt && food.heal() > 0.0F;
		if (food.temper() <= 0 || t >= MAX_TEMPER) {
			return new HorseMeal(eats, false, false, t);
		}
		t = Math.min(MAX_TEMPER, t + food.temper());
		if (random.applyAsInt(MAX_TEMPER) < t) {
			return new HorseMeal(true, true, true, t);
		}
		return new HorseMeal(true, true, false, Math.min(MAX_TEMPER, t + FAILED_MOUNT_TEMPER));
	}

	/** A companion is hurt when Skyrim's health fraction is short of full by more than rounding. */
	public static boolean hurt(float healthFrac) {
		return healthFrac < 0.995F;
	}

	/** The stand-in's name: the Skyrim name, marked when it's the player's. */
	public static String displayName(String skyrimName, int actorFlags) {
		String name = skyrimName == null || skyrimName.isBlank() ? "Animal" : skyrimName;
		return companion(actorFlags) ? name + " (tamed)" : name;
	}

	private static float clamp01(float v) {
		return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
	}
}
