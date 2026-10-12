package dev.skycraft.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.companion.CompanionRules.Kind;
import dev.skycraft.companion.CompanionRules.Use;
import dev.skycraft.link.Proto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

class CompanionRulesTest {
	private static int flags(int kind, boolean companion, boolean waiting) {
		return (kind << Proto.ACTOR_KIND_SHIFT) | (companion ? Proto.ACTOR_COMPANION : 0) | (waiting ? Proto.ACTOR_COMPANION_WAITING : 0);
	}

	private static final int WOLF = flags(Proto.ANIMAL_WOLF, false, false);
	private static final int DOG = flags(Proto.ANIMAL_DOG, false, false);
	private static final int HORSE = flags(Proto.ANIMAL_HORSE, false, false);
	private static final int MEEKO = flags(Proto.ANIMAL_DOG, true, false);
	private static final int MEEKO_SITTING = flags(Proto.ANIMAL_DOG, true, true);
	private static final int MY_HORSE = flags(Proto.ANIMAL_HORSE, true, true);

	/** A "random" that always rolls {@code value} (clamped below the bound). */
	private static IntUnaryOperator rolls(int value) {
		return bound -> Math.min(value, bound - 1);
	}

	@Test
	void readsTheKindAndStateFromSkyrimsFlags() {
		assertEquals(Kind.WOLF, CompanionRules.kind(WOLF | Proto.ACTOR_HOSTILE | Proto.ACTOR_IN_COMBAT));
		assertEquals(Kind.DOG, CompanionRules.kind(MEEKO));
		assertEquals(Kind.HORSE, CompanionRules.kind(MY_HORSE));
		assertEquals(Kind.NONE, CompanionRules.kind(Proto.ACTOR_HOSTILE | Proto.ACTOR_ESSENTIAL | Proto.ACTOR_SPAWNED));
		assertEquals(Kind.NONE, CompanionRules.kind(9 << Proto.ACTOR_KIND_SHIFT)); // a kind this Minecraft doesn't know
		assertFalse(CompanionRules.companion(WOLF));
		assertTrue(CompanionRules.companion(MEEKO));
		assertFalse(CompanionRules.waiting(MEEKO));
		assertTrue(CompanionRules.waiting(MEEKO_SITTING));
		for (Kind k : Kind.values()) {
			assertEquals(k, CompanionRules.kind(k.proto() << Proto.ACTOR_KIND_SHIFT));
		}
		assertTrue(Kind.DOG.wolfLike() && Kind.WOLF.wolfLike() && !Kind.HORSE.wolfLike() && !Kind.NONE.wolfLike());
	}

	@Test
	void theCompanionBitsDontTouchTheOthers() {
		int others = Proto.ACTOR_HOSTILE | Proto.ACTOR_DEAD | Proto.ACTOR_ESSENTIAL | Proto.ACTOR_IN_COMBAT | Proto.ACTOR_SPAWNED;
		assertEquals(0, others & (Proto.ACTOR_COMPANION | Proto.ACTOR_COMPANION_WAITING | Proto.ACTOR_KIND_MASK));
		assertEquals(0, Proto.ACTOR_KIND_MASK & (Proto.ACTOR_COMPANION | Proto.ACTOR_COMPANION_WAITING));
	}

	@Test
	void bonesForUntamedDogsAndWolves() {
		assertEquals(Use.TAME_BONE, CompanionRules.use(WOLF, true, false, false, "minecraft:bone", false));
		assertEquals(Use.TAME_BONE, CompanionRules.use(DOG | Proto.ACTOR_HOSTILE, true, false, false, "minecraft:bone", false));
		assertEquals(Use.TAME_BONE, CompanionRules.use(WOLF, false, false, false, "minecraft:bone", false)); // off hand too
		// Anything else on an untamed one is the stand-in's usual business (a hit, Skyrim's activation).
		assertEquals(Use.PASS, CompanionRules.use(WOLF, true, false, false, "minecraft:cooked_beef", true));
		assertEquals(Use.PASS, CompanionRules.use(WOLF, true, true, false, "", false));
		assertEquals(Use.PASS, CompanionRules.use(HORSE, true, false, false, "minecraft:bone", false));
		// Not a dog, wolf or horse; a quest's animal (Barbas); a dead one.
		assertEquals(Use.PASS, CompanionRules.use(Proto.ACTOR_HOSTILE, true, false, false, "minecraft:bone", false));
		assertEquals(Use.PASS, CompanionRules.use(DOG | Proto.ACTOR_ESSENTIAL, true, false, false, "minecraft:bone", false));
		assertEquals(Use.PASS, CompanionRules.use(WOLF | Proto.ACTOR_DEAD, true, false, false, "minecraft:bone", false));
	}

	@Test
	void horseFoodForUntamedHorses() {
		for (String food : new String[] { "minecraft:apple", "minecraft:wheat", "minecraft:golden_carrot", "minecraft:sugar", "minecraft:hay_block" }) {
			assertEquals(Use.FEED_HORSE, CompanionRules.use(HORSE, true, false, false, food, false), food);
		}
		assertEquals(Use.PASS, CompanionRules.use(HORSE, true, false, false, "minecraft:cooked_beef", true));
		assertEquals(Use.PASS, CompanionRules.use(HORSE, true, true, false, "", false)); // Skyrim's activation: steal a ride
	}

	@Test
	void anEmptyHandMakesACompanionSitOrFollow() {
		assertEquals(Use.TOGGLE, CompanionRules.use(MEEKO, true, true, false, "", false));
		assertEquals(Use.TOGGLE, CompanionRules.use(MEEKO_SITTING, true, true, true, "", false));
		assertEquals(Use.PASS, CompanionRules.use(MEEKO, false, true, false, "", false)); // only the main hand orders
		// A horse: a plain click rides it (Skyrim's activation); sneaking orders it.
		assertEquals(Use.PASS, CompanionRules.use(MY_HORSE, true, true, false, "", false));
		assertEquals(Use.TOGGLE, CompanionRules.use(MY_HORSE, true, true, true, "", false));
		// Skyrim's animal follower that isn't a dog, wolf or horse (a troll) still sits and follows.
		assertEquals(Use.TOGGLE, CompanionRules.use(Proto.ACTOR_COMPANION, true, true, false, "", false));
		// A sword or a block in hand is the usual click.
		assertEquals(Use.PASS, CompanionRules.use(MEEKO, true, false, false, "minecraft:iron_sword", false));
		assertEquals(Use.PASS, CompanionRules.use(MEEKO, true, false, false, "minecraft:bone", false));
	}

	@Test
	void foodHealsACompanion() {
		assertEquals(Use.HEAL, CompanionRules.use(MEEKO, true, false, false, "minecraft:cooked_beef", true));
		assertEquals(Use.HEAL, CompanionRules.use(MEEKO_SITTING, false, false, false, "minecraft:rotten_flesh", true));
		assertEquals(Use.HEAL, CompanionRules.use(MY_HORSE, true, false, false, "minecraft:apple", false));
		assertEquals(Use.HEAL, CompanionRules.use(MY_HORSE, true, false, true, "minecraft:hay_block", false));
		assertEquals(Use.PASS, CompanionRules.use(MY_HORSE, true, false, false, "minecraft:cooked_beef", true));
		assertEquals(Use.PASS, CompanionRules.use(MEEKO, true, false, false, "minecraft:apple", false));
		assertEquals(Use.PASS, CompanionRules.use(Proto.ACTOR_COMPANION, true, false, false, "minecraft:cooked_beef", true));
	}

	@Test
	void oneBoneInThree() {
		assertTrue(CompanionRules.boneTames(rolls(0)));
		assertFalse(CompanionRules.boneTames(rolls(1)));
		assertFalse(CompanionRules.boneTames(rolls(2)));
		Random random = new Random(4201);
		int tamed = 0;
		for (int i = 0; i < 30000; i++) {
			tamed += CompanionRules.boneTames(random::nextInt) ? 1 : 0;
		}
		assertEquals(10000, tamed, 300); // about a third
	}

	@Test
	void aWolfStaysAngryAfterAHit() {
		assertFalse(CompanionRules.angry(-1));
		assertTrue(CompanionRules.angry(0));
		assertTrue(CompanionRules.angry(CompanionRules.ANGRY_TICKS - 1));
		assertFalse(CompanionRules.angry(CompanionRules.ANGRY_TICKS));
	}

	@Test
	void wolfFoodHealsTwiceItsNutritionOfForty() {
		assertEquals(0.4, CompanionRules.wolfHealShare(8), 1e-6);   // cooked beef
		assertEquals(0.15, CompanionRules.wolfHealShare(3), 1e-6);  // raw beef
		assertEquals(0.2, CompanionRules.wolfHealShare(4), 1e-6);   // rotten flesh
		assertEquals(0.5, CompanionRules.wolfHealShare(10), 1e-6);  // rabbit stew
		assertEquals(0.05, CompanionRules.wolfHealShare(0), 1e-6);  // no food component: Animal.feed's fallback of 2
		assertEquals(1.0, CompanionRules.wolfHealShare(100), 1e-6);
	}

	@Test
	void horseFoodsAreVanillas() {
		assertEquals(8, CompanionRules.HORSE_FOODS.size());
		assertEquals(new CompanionRules.HorseFood(2.0F, 3), CompanionRules.horseFood("minecraft:wheat"));
		assertEquals(new CompanionRules.HorseFood(1.0F, 3), CompanionRules.horseFood("minecraft:sugar"));
		assertEquals(new CompanionRules.HorseFood(3.0F, 3), CompanionRules.horseFood("minecraft:apple"));
		assertEquals(new CompanionRules.HorseFood(4.0F, 5), CompanionRules.horseFood("minecraft:golden_carrot"));
		assertEquals(new CompanionRules.HorseFood(10.0F, 10), CompanionRules.horseFood("minecraft:golden_apple"));
		assertEquals(new CompanionRules.HorseFood(20.0F, 0), CompanionRules.horseFood("minecraft:hay_block"));
		assertNull(CompanionRules.horseFood("minecraft:bone"));
		assertNull(CompanionRules.horseFood(null));
		// Of an average horse's 22.5 health.
		assertEquals(20.0 / 22.5, CompanionRules.horseHealShare(CompanionRules.horseFood("minecraft:hay_block")), 1e-6);
		assertEquals(2.0 / 22.5, CompanionRules.horseHealShare(CompanionRules.horseFood("minecraft:wheat")), 1e-6);
		assertEquals(10.0 / 22.5, CompanionRules.horseHealShare(CompanionRules.horseFood("minecraft:golden_apple")), 1e-6);
	}

	@Test
	void aMealIsAMountAttempt() {
		CompanionRules.HorseFood apple = CompanionRules.horseFood("minecraft:apple");
		// Temper 0 + 3: a roll under 3 tames it.
		CompanionRules.HorseMeal lucky = CompanionRules.feedUntamedHorse(0, apple, false, rolls(2));
		assertTrue(lucky.eats() && lucky.tried() && lucky.tamed());
		assertEquals(3, lucky.temper());
		// Otherwise it throws its rider, as it were: 3 + 5 temper.
		CompanionRules.HorseMeal not = CompanionRules.feedUntamedHorse(0, apple, false, rolls(3));
		assertTrue(not.eats() && not.tried());
		assertFalse(not.tamed());
		assertEquals(8, not.temper());
		// Temper never passes 100, and at 95 + 5 every roll tames.
		assertEquals(100, CompanionRules.feedUntamedHorse(97, apple, false, rolls(99)).temper());
		assertTrue(CompanionRules.feedUntamedHorse(95, CompanionRules.horseFood("minecraft:golden_carrot"), false, rolls(99)).tamed());
		// Hay only heals: eaten by a hurt horse, no attempt; left alone by a healthy one.
		CompanionRules.HorseFood hay = CompanionRules.horseFood("minecraft:hay_block");
		CompanionRules.HorseMeal hurtHay = CompanionRules.feedUntamedHorse(20, hay, true, rolls(0));
		assertTrue(hurtHay.eats());
		assertFalse(hurtHay.tried() || hurtHay.tamed());
		assertEquals(20, hurtHay.temper());
		assertFalse(CompanionRules.feedUntamedHorse(20, hay, false, rolls(0)).eats());
	}

	@Test
	void applesTameAHorseInAFewMeals() {
		// Each apple adds 3 and a failed attempt 5 more, so a horse is tamed within 13 apples, mostly in 3 to 6.
		Random random = new Random(201);
		CompanionRules.HorseFood apple = CompanionRules.horseFood("minecraft:apple");
		int total = 0, most = 0;
		for (int horse = 0; horse < 2000; horse++) {
			int temper = 0, meals = 0;
			boolean tamed = false;
			while (!tamed) {
				CompanionRules.HorseMeal meal = CompanionRules.feedUntamedHorse(temper, apple, false, random::nextInt);
				meals++;
				tamed = meal.tamed();
				temper = meal.temper();
			}
			total += meals;
			most = Math.max(most, meals);
		}
		double average = total / 2000.0;
		assertTrue(average > 3.0 && average < 6.0, "average " + average);
		assertTrue(most <= 13, "most " + most);
	}

	@Test
	void hurtAndNames() {
		assertTrue(CompanionRules.hurt(0.5F));
		assertFalse(CompanionRules.hurt(1.0F));
		assertFalse(CompanionRules.hurt(0.999F));
		assertEquals("Meeko (tamed)", CompanionRules.displayName("Meeko", MEEKO));
		assertEquals("Wolf", CompanionRules.displayName("Wolf", WOLF));
		assertEquals("Animal (tamed)", CompanionRules.displayName("", MY_HORSE));
	}

	/** Proto.java and protocol/skycraft_protocol.h agree on everything the companions feature sends. */
	@Test
	void matchesTheProtocolHeader() throws IOException {
		Path header = null;
		for (Path dir = Path.of("").toAbsolutePath(); dir != null && header == null; dir = dir.getParent()) {
			Path candidate = dir.resolve("protocol/skycraft_protocol.h");
			if (Files.exists(candidate)) {
				header = candidate;
			}
		}
		assertNotNull(header, "protocol/skycraft_protocol.h not found above " + Path.of("").toAbsolutePath());
		String text = Files.readString(header);
		String[] expected = {
			"kActorCompanion = 1u << " + Integer.numberOfTrailingZeros(Proto.ACTOR_COMPANION),
			"kActorCompanionWaiting = 1u << " + Integer.numberOfTrailingZeros(Proto.ACTOR_COMPANION_WAITING),
			"kActorKindShift = " + Proto.ACTOR_KIND_SHIFT,
			"kActorKindMask = 0xFu << kActorKindShift",
			"kAnimalNone = " + Proto.ANIMAL_NONE,
			"kAnimalDog = " + Proto.ANIMAL_DOG,
			"kAnimalWolf = " + Proto.ANIMAL_WOLF,
			"kAnimalHorse = " + Proto.ANIMAL_HORSE,
			"kEvCompanion = " + Proto.EV_COMPANION,
			"kCompanionTame = " + Proto.COMPANION_TAME,
			"kCompanionFollow = " + Proto.COMPANION_FOLLOW,
			"kCompanionWait = " + Proto.COMPANION_WAIT,
			"kCompanionHeal = " + Proto.COMPANION_HEAL,
		};
		for (String line : expected) {
			assertTrue(text.contains(line), "the header should say " + line);
		}
		assertEquals(0xF << 8, Proto.ACTOR_KIND_MASK);
		// The event type is new: after the portal network's 11 and the Minecraft view's reserved 12 and 13.
		assertTrue(Proto.EV_COMPANION > Proto.EV_LEAVE_OTHER_DIMENSION && Proto.EV_LEAVE_OTHER_DIMENSION > Proto.EV_PORTAL_TRAVEL);
	}
}
