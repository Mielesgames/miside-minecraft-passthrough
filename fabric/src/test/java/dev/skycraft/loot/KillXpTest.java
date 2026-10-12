package dev.skycraft.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KillXpTest {
	@Test
	void levelOneIsLikeAZombie() {
		assertEquals(5, KillXp.forLevel(1));
		assertEquals(5, KillXp.forLevel(0));
	}

	@Test
	void growsWithLevelUpToTheCap() {
		int last = 0;
		for (int level = 1; level <= 100; level++) {
			int xp = KillXp.forLevel(level);
			assertTrue(xp >= last, "level " + level);
			assertTrue(xp <= KillXp.MAX);
			last = xp;
		}
		assertEquals(KillXp.MAX, KillXp.forLevel(81));
	}
}
