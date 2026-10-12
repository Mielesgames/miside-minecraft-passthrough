package dev.skycraft.deep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeepRulesTest {
	private static DeepRules defaults() {
		List<String> problems = new ArrayList<>();
		DeepRules rules = DeepRules.parse(DeepRules.DEFAULT_TEXT, problems);
		assertTrue(problems.isEmpty(), problems.toString());
		return rules;
	}

	@Test
	void defaultsParseCleanly() {
		DeepRules rules = defaults();
		assertTrue(rules.enabled());
		assertEquals(3, rules.maxAlive());
		assertEquals(45.0, rules.cooldownMinSeconds());
		assertEquals(90.0, rules.cooldownMaxSeconds());
		assertEquals(12.0, rules.minDistance());
		assertEquals(26.0, rules.maxDistance());
		assertEquals(64.0, rules.despawnDistance());
		assertEquals(8.0, rules.minDepth());
		assertEquals(0, rules.maxBlockLight());
		assertEquals(3, rules.bands().size());
	}

	@Test
	void depthBandsPickTheCreature() {
		DeepRules rules = defaults();
		assertEquals(DeepRules.NONE, rules.kindAt(0.0, 0));
		assertEquals(DeepRules.NONE, rules.kindAt(7.99, 0));
		assertEquals(DeepRules.SKEEVER, rules.kindAt(8.0, 0));
		assertEquals(DeepRules.SKEEVER, rules.kindAt(23.9, 5));
		assertEquals(DeepRules.SKELETON, rules.kindAt(24.0, 0));
		assertEquals(DeepRules.SKELETON, rules.kindAt(47.9, 7));
		assertEquals(DeepRules.DRAUGR, rules.kindAt(48.0, 0));
		assertEquals(DeepRules.DRAUGR, rules.kindAt(63.5, 3));
		assertEquals(DeepRules.DRAUGR, rules.kindAt(500.0, 3));
		assertEquals(DeepRules.NONE, rules.kindAt(Double.NaN, 0));
	}

	@Test
	void minDepthWinsOverBands() {
		DeepRules rules = DeepRules.parse("min_depth = 20\ndepth 0-30 = skeever\n", new ArrayList<>());
		assertEquals(DeepRules.NONE, rules.kindAt(10.0, 0));
		assertEquals(DeepRules.SKEEVER, rules.kindAt(20.0, 0));
		assertEquals(DeepRules.NONE, rules.kindAt(30.0, 0));
	}

	@Test
	void severalCreaturesInABandAreAllPicked() {
		List<String> problems = new ArrayList<>();
		DeepRules rules = DeepRules.parse("depth 8+ = draugr, Skeleton , skeever\n", problems);
		assertTrue(problems.isEmpty(), problems.toString());
		Set<Integer> seen = new HashSet<>();
		for (int pick = 0; pick < 30; pick++) {
			seen.add(rules.kindAt(30.0, pick));
		}
		assertEquals(Set.of(DeepRules.SKEEVER, DeepRules.SKELETON, DeepRules.DRAUGR), seen);
		assertEquals(rules.kindAt(30.0, -1), rules.kindAt(30.0, 2)); // negative picks still land in the band
	}

	@Test
	void cooldownStaysInRange() {
		DeepRules rules = defaults();
		assertEquals(45L * 20L, rules.cooldownTicks(0.0));
		assertEquals(90L * 20L, rules.cooldownTicks(1.0));
		assertEquals(Math.round(67.5 * 20.0), rules.cooldownTicks(0.5));
		assertEquals(45L * 20L, rules.cooldownTicks(-3.0));
	}

	@Test
	void capCountsLiveSpawnedCreatures() {
		DeepRules rules = defaults();
		assertTrue(rules.belowCap(0));
		assertTrue(rules.belowCap(2));
		assertFalse(rules.belowCap(3));
		assertFalse(rules.belowCap(4));
	}

	@Test
	void distances() {
		DeepRules rules = defaults();
		assertFalse(rules.inSpawnRange(11.9 * 11.9));
		assertTrue(rules.inSpawnRange(12.0 * 12.0));
		assertTrue(rules.inSpawnRange(20.0 * 20.0));
		assertTrue(rules.inSpawnRange(26.0 * 26.0));
		assertFalse(rules.inSpawnRange(26.1 * 26.1));
		assertFalse(rules.shouldDespawn(64.0 * 64.0));
		assertTrue(rules.shouldDespawn(64.1 * 64.1));
	}

	@Test
	void darkness() {
		DeepRules rules = defaults();
		assertTrue(rules.darkEnough(0));
		assertFalse(rules.darkEnough(1));
		assertTrue(DeepRules.parse("max_block_light = 7", new ArrayList<>()).darkEnough(7));
	}

	@Test
	void viewCone() {
		// Looking north (-z).
		assertTrue(DeepRules.inViewCone(0, 0, -20, 0, 0, -1));
		assertTrue(DeepRules.inViewCone(10, -3, -20, 0, 0, -1));
		assertFalse(DeepRules.inViewCone(0, 0, 20, 0, 0, -1)); // behind
		assertFalse(DeepRules.inViewCone(20, 0, 0, 0, 0, -1)); // straight to the side
		assertTrue(DeepRules.inViewCone(20, 0, -6, 0, 0, -1)); // 73 degrees off: still counts as seen
		assertFalse(DeepRules.inViewCone(20, 0, -5, 0, 0, -1)); // 76 degrees off
		assertTrue(DeepRules.inViewCone(0, 0, 0, 0, 0, -1));
	}

	@Test
	void canBeTurnedOff() {
		assertFalse(DeepRules.parse("enabled = false\n" + DeepRules.DEFAULT_TEXT.replace("enabled = true", ""), new ArrayList<>()).enabled());
		assertFalse(DeepRules.parse("max_alive = 0\ndepth 8+ = draugr", new ArrayList<>()).enabled());
		assertFalse(DeepRules.parse("enabled = true", new ArrayList<>()).enabled()); // no bands: nothing lives down there
	}

	@Test
	void badLinesAreReportedAndSkipped() {
		List<String> problems = new ArrayList<>();
		DeepRules rules = DeepRules.parse("""
			enabled = maybe
			max_alive = 99
			cooldown = 90-45
			distance = 2-200
			depth 8-24 = mudcrab
			depth 24 = skeleton
			depth 30-40 =
			nonsense
			colour = red
			depth 8-24 = skeever
			""", problems);
		assertEquals(9, problems.size(), problems.toString());
		assertTrue(rules.enabled());
		assertEquals(3, rules.maxAlive());
		assertEquals(45.0, rules.cooldownMinSeconds());
		assertEquals(12.0, rules.minDistance());
		assertEquals(1, rules.bands().size());
		assertEquals(DeepRules.SKEEVER, rules.kindAt(10.0, 0));
	}

	@Test
	void despawnDistanceKeepsClearOfTheSpawnRing() {
		List<String> problems = new ArrayList<>();
		DeepRules rules = DeepRules.parse("distance = 12-40\ndespawn_distance = 30\ndepth 8+ = draugr", problems);
		assertEquals(1, problems.size(), problems.toString());
		assertEquals(48.0, rules.despawnDistance());
	}

	@Test
	void kindNames() {
		assertEquals(DeepRules.SKEEVER, DeepRules.kindNamed("Skeever"));
		assertEquals(DeepRules.SKELETON, DeepRules.kindNamed("skeletons"));
		assertEquals(DeepRules.DRAUGR, DeepRules.kindNamed(" DRAUGR "));
		assertEquals(DeepRules.NONE, DeepRules.kindNamed("zombie"));
		assertEquals("draugr", DeepRules.kindName(DeepRules.DRAUGR));
	}

	@Test
	void pacerWaitsACooldownThenRetriesEverySecond() {
		DeepRules.Pacer pacer = new DeepRules.Pacer();
		assertFalse(pacer.started());
		assertFalse(pacer.shouldLook(0));
		pacer.restart(100, 900);
		assertFalse(pacer.shouldLook(100));
		assertFalse(pacer.shouldLook(999));
		assertTrue(pacer.shouldLook(1000));
		pacer.looked(1000);
		assertFalse(pacer.shouldLook(1010));
		assertEquals(10, pacer.ticksUntilLook(1010));
		assertTrue(pacer.shouldLook(1020));
		pacer.spawned(1020, 1200);
		assertFalse(pacer.shouldLook(2219));
		assertTrue(pacer.shouldLook(2220));
		assertEquals(0, pacer.ticksUntilLook(2300));
	}

	@Test
	void pacerResetForgetsAnOldServersTicks() {
		DeepRules.Pacer pacer = new DeepRules.Pacer();
		pacer.restart(100_000, 900); // a long session: the next spawn is due at tick 100900
		pacer.reset();
		assertFalse(pacer.started());
		assertFalse(pacer.shouldLook(50));
		pacer.restart(50, 900); // the new server, from its own tick count
		assertTrue(pacer.shouldLook(950));
	}
}
