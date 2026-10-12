package dev.skycraft.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.portal.PortalStore.Portal;
import dev.skycraft.portal.PortalStore.Target;
import dev.skycraft.worlds.FarLands;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Seamstone's list of places, and where the Far Lands give up Seamstone. */
class DestinationsTest {
	private static final int TAMRIEL = 0x3C, DRAGONSREACH = 0x000165A3;

	@Test
	void arrivalsOfVisitedWorldsThenEveryPortalButThisOne() {
		PortalStore s = new PortalStore();
		Portal whiterun = s.add(TAMRIEL, 0, 0, 64, 0, PortalStore.AXIS_X, 2, 3, 1, "Whiterun");
		Portal porch = s.add(DRAGONSREACH, PortalStore.INTERIOR, 10, 60, 10, PortalStore.AXIS_Z, 2, 3, 1, "");
		Portal nether = s.addIn(PortalKeys.NETHER, 0, 70, 0, PortalStore.AXIS_X, 2, 3, 1, "The Nether");
		Portal crack = s.addIn(PortalKeys.HOME, FarLands.EDGE + 1, 71, -1, PortalStore.AXIS_Z, 2, 3, -1, "The Far Lands");
		s.bind(whiterun.id(), nether.id());
		s.visit(PortalKeys.NETHER);
		s.visit(PortalKeys.HOME);
		List<Destinations.Entry> list = Destinations.list(s, s.get(crack.id()));
		// Home and the Nether were visited (Tamriel Wilds wasn't); then the portals, this one left out.
		assertEquals(5, list.size());
		assertEquals(new Target.ToArrival(PortalKeys.HOME), list.get(0).target());
		assertEquals("Home: Steve's camp", list.get(0).label());
		assertEquals(new Target.ToArrival(PortalKeys.NETHER), list.get(1).target());
		assertEquals(new Target.ToPortal(whiterun.id()), list.get(2).target());
		assertEquals("Whiterun", list.get(2).label());
		assertEquals("minecraft:obsidian", list.get(2).icon());
		assertEquals("An unnamed place indoors", list.get(3).label());
		assertEquals("minecraft:lantern", list.get(3).icon());
		assertTrue(list.get(3).detail().startsWith("Skyrim, indoors, portal 2"));
		assertEquals(new Target.ToPortal(nether.id()), list.get(4).target());
		assertEquals("minecraft:netherrack", list.get(4).icon());
		assertTrue(list.stream().noneMatch(e -> e.target().equals(new Target.ToPortal(crack.id()))));
		// From Whiterun its partner is marked.
		List<Destinations.Entry> fromWhiterun = Destinations.list(s, s.get(whiterun.id()));
		assertTrue(fromWhiterun.stream().anyMatch(e -> e.target().equals(new Target.ToPortal(nether.id())) && e.detail().endsWith("(bound to this one)")));
		// A portal not in the network yet lists everything.
		assertEquals(6, Destinations.list(s, null).size());
	}

	@Test
	void aDoubleChestAtMost() {
		PortalStore s = new PortalStore();
		for (int i = 0; i < 70; i++) {
			s.add(TAMRIEL, 0, i * 10, 64, 0, PortalStore.AXIS_X, 2, 3, 1, "Place " + i);
		}
		s.visit(PortalKeys.TAMRIEL_WILDS);
		List<Destinations.Entry> list = Destinations.list(s, null);
		assertEquals(Destinations.MAX, list.size());
		assertEquals(new Target.ToArrival(PortalKeys.TAMRIEL_WILDS), list.get(0).target());
		assertEquals(6, Destinations.rows(list.size()));
		assertEquals(1, Destinations.rows(0));
		assertEquals(1, Destinations.rows(9));
		assertEquals(2, Destinations.rows(10));
	}

	@Test
	void seamstoneLiesPastTheEdgeInHomeOnly() {
		int edge = FarLands.EDGE;
		assertTrue(Ending.dropsSeamstone(true, edge, 0, true, false));
		assertTrue(Ending.dropsSeamstone(true, edge + 5, 0, true, false)); // the crack's walls and floor
		assertTrue(Ending.dropsSeamstone(true, 0, -edge - 3, true, false)); // any side of the world
		assertFalse(Ending.dropsSeamstone(true, edge - 1, 0, true, false)); // still Steve's land
		assertFalse(Ending.dropsSeamstone(false, edge, 0, true, false)); // the same spot in another world
		assertFalse(Ending.dropsSeamstone(true, edge, 0, false, false)); // a torch, a plant, a bed
		assertFalse(Ending.dropsSeamstone(true, edge, 0, true, true)); // one on them already
		assertEquals("Past the Far Lands", Ending.TITLE);
		assertTrue(Ending.LINES.get(0).contains("PLACEHOLDER"));
	}
}
