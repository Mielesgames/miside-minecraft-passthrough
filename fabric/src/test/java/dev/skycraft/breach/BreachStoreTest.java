package dev.skycraft.breach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.link.Proto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BreachStoreTest {
	@Test
	void roundTripsEveryField() {
		BreachStore store = new BreachStore();
		BreachRoom a = new BreachRoom(0x0000003C, 0xFF00ABCD, -1234, -20, 5678, 3, 2, Proto.THEME_DWEMER);
		BreachRoom b = new BreachRoom(0x0000003C, 0x0001A2B3, 10, 40, 10, 0, 1, Proto.THEME_NORDIC);
		store.put(new BreachStore.Entry(a, BreachStore.BUILT | BreachStore.LOOT, "Mzinchaleft"));
		store.put(new BreachStore.Entry(b, 0, "Bleak Falls Barrow"));
		List<String> problems = new ArrayList<>();
		BreachStore back = BreachStore.parse(store.lines(), problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals(2, back.size());
		BreachStore.Entry ea = back.get(0x3C, 0xFF00ABCD);
		assertNotNull(ea);
		assertEquals(a, ea.room());
		assertEquals("Mzinchaleft", ea.name());
		assertTrue(ea.has(BreachStore.BUILT) && ea.has(BreachStore.LOOT) && !ea.has(BreachStore.ENTERED));
		assertEquals("Bleak Falls Barrow", back.get(0x3C, 0x0001A2B3).name());
	}

	@Test
	void noNameAndBadLines() {
		List<String> problems = new ArrayList<>();
		BreachStore s = BreachStore.parse(List.of(
			BreachStore.HEADER,
			"0000003C 00000001 0 10 0 2 1 0 0",
			"0000003C 00000002 0 10 0 9 1 0 0 Bad Facing",
			"not a line",
			"0000003C 0000000Z 0 10 0 2 1 0 0 Bad Hex",
			"",
			"0000003C 00000003 100 10 100 1 2 77 5 Odd Theme"), problems);
		assertEquals(3, problems.size(), problems.toString());
		assertEquals(2, s.size());
		assertEquals("", s.get(0x3C, 1).name());
		assertEquals(Proto.THEME_CAVE, s.get(0x3C, 3).room().theme());
		assertTrue(s.get(0x3C, 3).has(BreachStore.ENTERED));
	}

	@Test
	void laterLinesWinAndFlagsOnlyGrow() {
		BreachRoom r = new BreachRoom(0x3C, 9, 0, 10, 0, 0, 1, Proto.THEME_LAIR);
		BreachStore.Entry e = new BreachStore.Entry(r, 0, "Movarth's Lair");
		BreachStore.Entry built = e.with(BreachStore.BUILT);
		assertFalse(e.has(BreachStore.BUILT));
		assertTrue(built.with(BreachStore.ENTERED).has(BreachStore.BUILT));
		List<String> problems = new ArrayList<>();
		BreachStore s = BreachStore.parse(List.of(BreachStore.format(e), BreachStore.format(built)), problems);
		assertEquals(1, s.size());
		assertTrue(s.get(0x3C, 9).has(BreachStore.BUILT));
	}

	@Test
	void findsRoomsByCellAndWorld() {
		BreachStore s = new BreachStore();
		BreachRoom r = new BreachRoom(0x3C, 9, 0, 10, 0, 0, 1, Proto.THEME_LAIR);
		s.put(new BreachStore.Entry(r, 0, ""));
		int x = r.x(1, 1), z = r.z(1, 1);
		assertSame(s.get(0x3C, 9), s.roomAt(0x3C, x, 10, z));
		assertNull(s.roomAt(0x3D, x, 10, z)); // another worldspace at the same coordinates
		assertSame(s.get(0x3C, 9), s.anyRoomAt(x, 10, z));
		assertNull(s.roomAt(0x3C, x, 30, z));
		// A room for another door in the way; the same door's own room isn't.
		assertNotNull(s.overlapping(new BreachRoom(0x3C, 10, 2, 11, 2, 1, 1, Proto.THEME_CAVE), 1));
		assertNull(s.overlapping(new BreachRoom(0x3C, 9, 2, 11, 2, 1, 1, Proto.THEME_CAVE), 1));
		assertNull(s.overlapping(new BreachRoom(0x3D, 10, 2, 11, 2, 1, 1, Proto.THEME_CAVE), 1));
	}
}
