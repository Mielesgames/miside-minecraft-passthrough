package dev.skycraft.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeaturesTest {
	@Test
	void defaultsTurnEverythingOn() {
		List<String> problems = new ArrayList<>();
		Map<Features.Feature, Boolean> on = Features.parse(Features.DEFAULT_TEXT, problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals(Features.Feature.values().length, on.size());
		on.values().forEach(v -> assertTrue(v));
		for (Features.Feature f : Features.Feature.values()) {
			assertTrue(Features.DEFAULT_TEXT.contains(f.key + " = on"), f.key);
		}
	}

	@Test
	void switchesOffAndKeepsTheRestOn() {
		List<String> problems = new ArrayList<>();
		Map<Features.Feature, Boolean> on = Features.parse("merchants = off\nKILL_XP = No  # comment\n", problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertFalse(on.get(Features.Feature.MERCHANTS));
		assertFalse(on.get(Features.Feature.KILL_XP));
		assertTrue(on.get(Features.Feature.DEEP_CREATURES));
	}

	@Test
	void reportsBadLines() {
		List<String> problems = new ArrayList<>();
		Map<Features.Feature, Boolean> on = Features.parse("thuum = on\nmerchants maybe\nmerchants = maybe\n", problems);
		assertEquals(3, problems.size(), problems.toString());
		assertTrue(on.get(Features.Feature.MERCHANTS));
	}
}
