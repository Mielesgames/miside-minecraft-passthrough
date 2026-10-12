package dev.skycraft.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DungeonLootTest {
	private static SkyLink.Item item(int source, int container, boolean chest) {
		return new SkyLink.Item(0x1234, 1, 0, 0, source, "Iron Ingot", container, chest, false);
	}

	@Test
	void defaultsParseCleanly() {
		List<String> problems = new ArrayList<>();
		List<DungeonLoot.Table> tables = DungeonLoot.parse(DungeonLoot.DEFAULT_TEXT, problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals(5, tables.size());
		assertEquals("minecraft:chests/simple_dungeon", tables.getFirst().id());
	}

	@Test
	void badLinesAreReportedOnce() {
		List<String> problems = new ArrayList<>();
		List<DungeonLoot.Table> tables = DungeonLoot.parse("minecraft:chests/a x\nnot a table\nminecraft:chests/b 0\nminecraft:chests/c\n", problems);
		assertEquals(3, problems.size(), problems.toString());
		assertEquals(1, tables.size());
		assertEquals(1, tables.getFirst().weight());
	}

	@Test
	void pickFollowsWeights() {
		List<DungeonLoot.Table> tables = List.of(new DungeonLoot.Table("a:x", 9), new DungeonLoot.Table("a:y", 1));
		Map<String, Integer> counts = new HashMap<>();
		Random random = new Random(7);
		for (int i = 0; i < 10000; i++) {
			counts.merge(DungeonLoot.pick(tables, random), 1, Integer::sum);
		}
		assertTrue(counts.get("a:x") > 8500 && counts.get("a:y") > 500, counts.toString());
		assertNull(DungeonLoot.pick(List.of(), random));
	}

	@Test
	void rollsOncePerDungeonChest() {
		Set<Integer> opened = new HashSet<>();
		assertTrue(DungeonLoot.shouldRoll(item(Proto.SRC_CONTAINER, 0xABC, true), Proto.PLACE_DUNGEON | Proto.PLACE_INTERIOR, opened));
		assertFalse(DungeonLoot.shouldRoll(item(Proto.SRC_CONTAINER, 0xABC, true), Proto.PLACE_DUNGEON, opened));
		assertTrue(DungeonLoot.shouldRoll(item(Proto.SRC_CONTAINER, 0xABD, true), Proto.PLACE_DUNGEON, opened));
	}

	@Test
	void aBadLineInTheOpenedFileDoesNotForgetTheChestsAfterIt() {
		Set<Integer> opened = new HashSet<>();
		// The second line was cut short by a crash and the next write ran on.
		int bad = DungeonLoot.parseOpened(List.of("0001A2B3", "0002B3C40003C4D5", "", "FF000ABC\r", "not hex"), opened);
		assertEquals(2, bad);
		assertEquals(Set.of(0x0001A2B3, 0xFF000ABC), opened);
	}

	@Test
	void ignoresTownsBarrelsCorpsesAndUnknownContainers() {
		Set<Integer> opened = new HashSet<>();
		assertFalse(DungeonLoot.shouldRoll(item(Proto.SRC_CONTAINER, 0xABC, true), Proto.PLACE_TOWN | Proto.PLACE_INTERIOR, opened));
		assertFalse(DungeonLoot.shouldRoll(item(Proto.SRC_CONTAINER, 0xABC, false), Proto.PLACE_DUNGEON, opened));
		assertFalse(DungeonLoot.shouldRoll(item(Proto.SRC_CORPSE, 0xABC, true), Proto.PLACE_DUNGEON, opened));
		assertFalse(DungeonLoot.shouldRoll(item(Proto.SRC_CONTAINER, 0, true), Proto.PLACE_DUNGEON, opened));
		assertTrue(opened.isEmpty());
	}
}
