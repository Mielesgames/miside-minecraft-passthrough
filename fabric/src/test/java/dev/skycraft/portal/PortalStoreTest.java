package dev.skycraft.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.portal.PortalStore.Portal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortalStoreTest {
	private static final int TAMRIEL = 0x3C, SOLSTHEIM = 0x04000800, DRAGONSREACH = 0x000165A3;

	@Test
	void roundTripsEveryFieldAndTheNextId() {
		PortalStore s = new PortalStore();
		Portal a = s.add(TAMRIEL, 0, -1234, 40, 5678, PortalStore.AXIS_X, 2, 3, -1, "Whiterun");
		Portal b = s.add(DRAGONSREACH, PortalStore.INTERIOR, 10, -5, 10, PortalStore.AXIS_Z, 4, 5, 1, "Dragonsreach Great Porch");
		Portal c = s.add(SOLSTHEIM, 0, 300, 12, -300, PortalStore.AXIS_X, 2, 3, 1, "");
		Portal d = s.add(0xFE0A0D62, PortalStore.INTERIOR, 1, 2, 3, PortalStore.AXIS_Z, 21, 21, -1, "A light mod's cave"); // FormID above 0x7FFFFFFF
		assertEquals(PortalStore.Bind.BOUND, s.bind(a.id(), b.id()));
		s.remove(c.id()); // its id is never handed out again
		List<String> problems = new ArrayList<>();
		PortalStore back = PortalStore.parse(s.lines(), problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals(3, back.size());
		assertEquals(s.get(a.id()), back.get(a.id()));
		assertEquals(s.get(b.id()), back.get(b.id()));
		assertEquals(d, back.get(d.id()));
		assertTrue(back.get(b.id()).interior());
		assertEquals("Dragonsreach Great Porch", back.get(b.id()).place());
		assertEquals(5, back.nextId());
		assertEquals(5, back.add(TAMRIEL, 0, 0, 0, 0, PortalStore.AXIS_X, 2, 3, 1, "x").id());
	}

	@Test
	void badLinesAndOneSidedBonds() {
		List<String> problems = new ArrayList<>();
		PortalStore s = PortalStore.parse(List.of(
			PortalStore.HEADER,
			"next 9",
			"1 0000003C 0 0 10 0 X 2 3 1 2 Riverwood",
			"2 0000003C 0 50 10 0 Z 2 3 -1 1",
			"3 0000003C 0 90 10 0 Q 2 3 1 0 Bad Axis",
			"4 0000003C 0 90 10 0 X 2 3 0 0 Bad Side",
			"5 0000003C 0 90 10 0 X 22 3 1 0 Too Wide",
			"6 0000003C 0 90 10 0 X 2 3 1 1 Half Bond",
			"7 0000003G 0 90 10 0 X 2 3 1 0 Bad Hex",
			"not a line",
			"8 0000003C 0 120 10 0 X 2 3 1 8 Bound To Itself"), problems);
		// Five bad lines, two bonds that aren't mutual (6 -> 1, which is bound to 2; 8 -> 8).
		assertEquals(7, problems.size(), problems.toString());
		assertEquals(4, s.size());
		assertEquals(2, s.get(1).partner());
		assertEquals(1, s.get(2).partner());
		assertEquals("", s.get(2).place());
		assertFalse(s.get(6).bound());
		assertFalse(s.get(8).bound());
		assertEquals(9, s.nextId());
	}

	@Test
	void bondsArePairsForGood() {
		PortalStore s = new PortalStore();
		Portal a = s.add(TAMRIEL, 0, 0, 10, 0, PortalStore.AXIS_X, 2, 3, 1, "Whiterun");
		Portal b = s.add(SOLSTHEIM, 0, 0, 10, 0, PortalStore.AXIS_X, 2, 3, 1, "Raven Rock");
		Portal c = s.add(TAMRIEL, 0, 40, 10, 0, PortalStore.AXIS_X, 2, 3, 1, "Riverwood");
		assertNull(s.destination(a));
		assertEquals(PortalStore.Bind.SAME, s.bind(a.id(), a.id()));
		assertEquals(PortalStore.Bind.MISSING, s.bind(a.id(), 99));
		assertEquals(PortalStore.Bind.BOUND, s.bind(a.id(), b.id()));
		assertEquals(PortalStore.Bind.ALREADY_BOUND, s.bind(c.id(), a.id()));
		assertEquals(PortalStore.Bind.ALREADY_BOUND, s.bind(b.id(), c.id()));
		assertEquals(b.id(), s.destination(s.get(a.id())).id());
		assertEquals(a.id(), s.destination(s.get(b.id())).id());
		assertNull(s.destination(s.get(c.id())));
		// A portal going out frees its partner, which can be bound again.
		assertNotNull(s.remove(b.id()));
		assertFalse(s.get(a.id()).bound());
		assertNull(s.remove(b.id()));
		assertEquals(PortalStore.Bind.BOUND, s.bind(c.id(), a.id()));
	}

	@Test
	void findsPortalsByWorldAndBlock() {
		PortalStore s = new PortalStore();
		Portal x = s.add(TAMRIEL, 0, 100, 20, 200, PortalStore.AXIS_X, 3, 4, 1, "Whiterun");
		Portal z = s.add(DRAGONSREACH, PortalStore.INTERIOR, 100, 20, 210, PortalStore.AXIS_Z, 2, 3, 1, "Dragonsreach");
		assertSame(s.get(x.id()), s.at(TAMRIEL, 102, 23, 200));
		assertNull(s.at(TAMRIEL, 103, 20, 200));
		assertNull(s.at(TAMRIEL, 100, 24, 200));
		assertNull(s.at(TAMRIEL, 100, 20, 201));
		assertNull(s.at(SOLSTHEIM, 100, 20, 200)); // another world at the same coordinates
		assertSame(s.get(x.id()), s.anyAt(100, 20, 200));
		assertSame(s.get(z.id()), s.at(DRAGONSREACH, 100, 22, 211));
		assertNull(s.at(DRAGONSREACH, 101, 22, 211));
		assertTrue(x.sameShape(100, 20, 200, PortalStore.AXIS_X, 3, 4));
		assertFalse(x.sameShape(100, 20, 200, PortalStore.AXIS_X, 2, 4));
		// The frame: one block round the portal blocks, in their plane.
		assertEquals(List.of(s.get(x.id())), s.touching(99, 19, 200));
		assertEquals(List.of(s.get(x.id())), s.touching(103, 24, 200));
		assertTrue(s.touching(104, 20, 200).isEmpty());
		assertTrue(s.touching(101, 20, 201).isEmpty());
		assertEquals(List.of(s.get(z.id())), s.touching(100, 23, 212));
		// A rebuilt frame of another size over the same blocks.
		assertEquals(List.of(s.get(x.id())), s.overlapping(101, 18, 200, PortalStore.AXIS_X, 2, 3));
		assertTrue(s.overlapping(101, 18, 200, PortalStore.AXIS_X, 2, 2).isEmpty());
		assertEquals(2, s.overlapping(100, 20, 200, PortalStore.AXIS_Z, 11, 3).size());
	}

	@Test
	void exitIsInFrontOnTheBoundSideFacingAway() {
		// Blocks along X at z = 50: the exit side is +Z (south) or -Z (north).
		Portal south = new Portal(1, TAMRIEL, 0, 10, 64, 50, PortalStore.AXIS_X, 2, 3, 1, 0, "");
		assertArrayEquals(new double[] { 11.0, 64.0, 51.5, 0.0 }, south.exit());
		assertArrayEquals(new double[] { 11.0, 64.0, 49.5, 180.0 }, south.withSide(-1).exit());
		// Blocks along Z at x = -7: the exit side is +X (east, yaw -90) or -X (west, yaw 90).
		Portal east = new Portal(2, TAMRIEL, 0, -7, 64, 20, PortalStore.AXIS_Z, 3, 4, 1, 0, "");
		assertArrayEquals(new double[] { -5.5, 64.0, 21.5, -90.0 }, east.exit());
		assertArrayEquals(new double[] { -7.5, 64.0, 21.5, 90.0 }, east.withSide(-1).exit());
		assertArrayEquals(new double[] { -6.5, 66.0, 21.5 }, east.center());
		// The side a player stands on.
		assertEquals(1, PortalStore.sideOf(PortalStore.AXIS_X, 10, 50, 11.0, 51.2));
		assertEquals(-1, PortalStore.sideOf(PortalStore.AXIS_X, 10, 50, 11.0, 49.9));
		assertEquals(1, PortalStore.sideOf(PortalStore.AXIS_Z, -7, 20, -6.0, 21.0));
		assertEquals(-1, PortalStore.sideOf(PortalStore.AXIS_Z, -7, 20, -7.2, 21.0));
	}

	@Test
	void dimensionPortalsRoundTripAndKeepToTheirWorld() {
		PortalStore s = new PortalStore();
		Portal mirror = s.add(TAMRIEL, 0, 0, 64, 0, PortalStore.AXIS_X, 2, 3, 1, "Whiterun");
		Portal nether = s.addIn(PortalKeys.NETHER, 0, 64, 0, PortalStore.AXIS_X, 2, 3, -1, "The Nether");
		Portal wilds = s.addIn(PortalKeys.TAMRIEL_WILDS, 90, 72, 307, PortalStore.AXIS_X, 2, 3, -1, "Tamriel Wilds");
		assertEquals(PortalStore.Bind.BOUND, s.bind(mirror.id(), nether.id()));
		assertTrue(s.get(mirror.id()).inMirror());
		assertFalse(s.get(nether.id()).inMirror());
		// The same blocks in the mirror and in the Nether are different portals.
		assertSame(s.get(mirror.id()), s.at(TAMRIEL, 1, 65, 0));
		assertSame(s.get(mirror.id()), s.anyAt(1, 65, 0));
		assertSame(s.get(nether.id()), s.in(PortalKeys.NETHER, 1, 65, 0));
		assertNull(s.in(PortalKeys.TAMRIEL_WILDS, 1, 65, 0));
		assertEquals(List.of(s.get(mirror.id())), s.touching(-1, 63, 0));
		assertEquals(List.of(s.get(nether.id())), s.touching(PortalKeys.NETHER, -1, 63, 0));
		assertEquals(List.of(s.get(wilds.id())), s.overlapping(PortalKeys.TAMRIEL_WILDS, 91, 72, 307, PortalStore.AXIS_X, 2, 3));
		assertTrue(s.overlapping(PortalKeys.NETHER, 91, 72, 307, PortalStore.AXIS_X, 2, 3).isEmpty());
		assertEquals("2 minecraft:the_nether 0 0 64 0 X 2 3 -1 1 The Nether", PortalStore.format(s.get(nether.id())));
		List<String> problems = new ArrayList<>();
		PortalStore back = PortalStore.parse(s.lines(), problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals(s.get(mirror.id()), back.get(mirror.id()));
		assertEquals(s.get(nether.id()), back.get(nether.id()));
		assertEquals(s.get(wilds.id()), back.get(wilds.id()));
		assertEquals(new PortalStore.Target.ToPortal(nether.id()), back.lead(back.get(mirror.id())));
		assertEquals(new PortalStore.Target.ToPortal(mirror.id()), back.lead(back.get(nether.id())));
		assertNull(back.lead(back.get(wilds.id())));
	}

	@Test
	void oneWayOpeningsLeadToAnArrivalAndCountAsBound() {
		PortalStore s = new PortalStore();
		Portal wound = s.add(TAMRIEL, 0, 800, 513, 682, PortalStore.AXIS_Z, 2, 3, 1, "Throat of the World");
		Portal other = s.add(TAMRIEL, 0, 0, 64, 0, PortalStore.AXIS_X, 2, 3, 1, "Riverwood");
		assertEquals(PortalStore.Bind.BOUND, s.openOneWay(wound.id(), PortalKeys.HOME));
		assertTrue(s.leads(s.get(wound.id())));
		assertFalse(s.get(wound.id()).bound());
		assertEquals(new PortalStore.Target.ToArrival(PortalKeys.HOME), s.lead(s.get(wound.id())));
		assertEquals(PortalStore.Bind.ALREADY_BOUND, s.bind(wound.id(), other.id()));
		assertEquals(PortalStore.Bind.ALREADY_BOUND, s.openOneWay(wound.id(), PortalKeys.NETHER));
		assertEquals(PortalStore.Bind.MISSING, s.openOneWay(99, PortalKeys.HOME));
		List<String> problems = new ArrayList<>();
		PortalStore back = PortalStore.parse(s.lines(), problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals(PortalKeys.HOME, back.oneWay(wound.id()));
		// Gone: the opening goes with it.
		back.remove(wound.id());
		assertNull(back.oneWay(wound.id()));
		assertTrue(back.lines().stream().noneMatch(l -> l.startsWith("oneway")));
	}

	@Test
	void arrivalsVisitsAndEndingsAreKept() {
		PortalStore s = new PortalStore();
		java.util.UUID steve = java.util.UUID.fromString("00000000-0000-0000-0000-00000000beef");
		assertNull(s.arrival(PortalKeys.NETHER));
		s.setArrival(PortalKeys.NETHER, new PortalStore.Arrival(4.5, 71.0, -7.5, 0.0f));
		assertTrue(s.visit(PortalKeys.NETHER));
		assertFalse(s.visit(PortalKeys.NETHER));
		assertFalse(s.visit("not a dimension"));
		assertTrue(s.seeEnding(steve));
		assertFalse(s.seeEnding(steve));
		List<String> problems = new ArrayList<>();
		PortalStore back = PortalStore.parse(s.lines(), problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals(new PortalStore.Arrival(4.5, 71.0, -7.5, 0.0f), back.arrival(PortalKeys.NETHER));
		assertTrue(back.visited(PortalKeys.NETHER));
		assertFalse(back.visited(PortalKeys.HOME));
		assertTrue(back.sawEnding(steve));
	}

	@Test
	void badDimensionLines() {
		List<String> problems = new ArrayList<>();
		PortalStore s = PortalStore.parse(List.of(
			"1 minecraft:the_nether 0 0 64 0 X 2 3 1 2 The Nether",
			"2 0000003C 0 0 64 0 X 2 3 1 1 Whiterun",
			"3 bad:dim:id 0 0 64 0 X 2 3 1 0 Bad",
			"4 0000003C 0 9 64 0 X 2 3 1 0 Riverwood",
			"oneway 4 skycraft:home",
			"oneway 2 skycraft:home",
			"oneway 77 skycraft:home",
			"oneway 4",
			"arrival minecraft:the_nether 1 2",
			"arrival minecraft:the_nether 1 2 3 NaN",
			"visited nowhere",
			"ending not-a-uuid"), problems);
		// A bad dimension, a oneway on a bound portal and on a missing one, a short oneway, two bad arrivals,
		// a bad visit, a bad uuid.
		assertEquals(8, problems.size(), problems.toString());
		assertEquals(3, s.size());
		assertEquals(PortalKeys.NETHER, s.get(1).dimension());
		assertEquals(2, s.get(1).partner());
		assertEquals(PortalKeys.HOME, s.oneWay(4));
		assertNull(s.oneWay(2));
		assertNull(s.arrival(PortalKeys.NETHER));
	}

	private static void assertArrayEquals(double[] want, double[] got) {
		assertEquals(want.length, got.length);
		for (int i = 0; i < want.length; i++) {
			assertEquals(want[i], got[i], 1.0E-9);
		}
	}
}
