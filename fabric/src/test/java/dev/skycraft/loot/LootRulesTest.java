package dev.skycraft.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LootRulesTest {
	private static LootRules defaults() {
		List<String> problems = new ArrayList<>();
		LootRules rules = LootRules.parse(LootRules.DEFAULT_TEXT, problems);
		assertTrue(problems.isEmpty(), problems.toString());
		return rules;
	}

	@Test
	void defaultsParseCleanly() {
		assertEquals(11, defaults().rules().size());
	}

	@Test
	void matchesWholeWordsIgnoringCase() {
		LootRules rules = defaults();
		assertEquals("minecraft:beef", rules.match("Deer").drops().getFirst().item());
		assertNotNull(rules.match("Cave Bear"));
		assertNotNull(rules.match("Snowy Sabre Cat"));
		assertNotNull(rules.match("Giant Mudcrab"));
		assertNotNull(rules.match("ice wolf"));
	}

	@Test
	void ignoresPeopleAndPartialWords() {
		LootRules rules = defaults();
		assertNull(rules.match("Werewolf"));
		assertNull(rules.match("Bandit Marauder"));
		assertNull(rules.match("Lydia"));
		assertNull(rules.match(""));
		assertNull(rules.match(null));
	}

	@Test
	void entriesOfSeveralWordsNeedAllOfThem() {
		List<String> problems = new ArrayList<>();
		LootRules rules = LootRules.parse("""
			sabre cat, ice-wraith = minecraft:string 1-2
			cat = minecraft:cod 1
			""", problems);
		assertTrue(problems.isEmpty(), problems.toString());
		// Before, "sabre cat" was one word that no name has, so the sabre cat fell through to "cat".
		assertEquals("minecraft:string", rules.match("Snowy Sabre Cat").drops().getFirst().item());
		assertEquals("minecraft:string", rules.match("Ice Wraith").drops().getFirst().item());
		assertEquals("minecraft:cod", rules.match("Cat").drops().getFirst().item());
		assertNull(rules.match("Sabre Tooth"));
	}

	@Test
	void rollsWithinRange() {
		LootRules.Rule deer = defaults().match("Deer");
		Random random = new Random(42);
		for (int i = 0; i < 500; i++) {
			for (LootRules.Drop d : LootRules.roll(deer, random)) {
				if (d.item().equals("minecraft:beef")) {
					assertTrue(d.min() >= 1 && d.min() <= 3, "beef " + d.min());
				} else {
					assertEquals("minecraft:leather", d.item());
					assertTrue(d.min() >= 1 && d.min() <= 2, "leather " + d.min());
				}
			}
		}
	}

	@Test
	void reportsBadLinesAndKeepsGoodOnes() {
		List<String> problems = new ArrayList<>();
		LootRules rules = LootRules.parse("""
			# comment
			deer = minecraft:beef 1-3
			no equals sign here
			goat = minecraft:mutton x-2
			skeever = minecraft:rotten_flesh 5-2
			cow = minecraft:beef
			""", problems);
		assertEquals(2, rules.rules().size());
		assertEquals(3, problems.size(), problems.toString());
		assertEquals(1, rules.match("Cow").drops().getFirst().max());
	}
}
