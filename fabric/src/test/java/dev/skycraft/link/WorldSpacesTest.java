package dev.skycraft.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldSpacesTest {
	private static final int OROTHEIM = 0x00015233;          // an interior cell
	private static final int REDORANS_RETREAT = 0x000152C3;  // another
	private static final int WHITERUN = 0x0001A26F;          // a city worldspace (shares Tamriel's coordinates in Skyrim)

	@Test
	void theSpiralStartsAtTheOriginAndGoesRound() {
		int[][] first = { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 }, { -1, 0 }, { -1, -1 }, { 0, -1 }, { 1, -1 }, { 2, -1 }, { 2, 0 } };
		for (int n = 0; n < first.length; n++) {
			int[] c = WorldSpaces.cell(n);
			assertEquals(first[n][0], c[0], "space " + n + " x");
			assertEquals(first[n][1], c[1], "space " + n + " z");
		}
	}

	@Test
	void everySpaceHasItsOwnCellRingByRing() {
		Set<Long> seen = new HashSet<>();
		int[] prev = null;
		for (int n = 0; n < 40_000; n++) {
			int[] c = WorldSpaces.cell(n);
			assertTrue(seen.add(((long) c[0] << 32) | (c[1] & 0xFFFFFFFFL)), "space " + n + " repeats a cell");
			assertEquals(WorldSpaces.ring(n), Math.max(Math.abs(c[0]), Math.abs(c[1])), "space " + n + " ring");
			assertEquals(n, WorldSpaces.spaceAtCell(c[0], c[1]), "space at cell of " + n);
			if (prev != null) {
				assertEquals(1, Math.abs(c[0] - prev[0]) + Math.abs(c[1] - prev[1]), "space " + n + " is next to the one before");
			}
			prev = c;
		}
		// Rings are filled before the next starts: everything within ring 50 is a space under 101 x 101.
		for (int x = -50; x <= 50; x++) {
			for (int z = -50; z <= 50; z++) {
				assertTrue(WorldSpaces.spaceAtCell(x, z) < 101 * 101, "cell " + x + " " + z);
			}
		}
	}

	@Test
	void tamrielIsAtTheOriginAndTheOthersFarApart() {
		WorldSpaces t = new WorldSpaces();
		assertEquals(0, t.spaceOf(WorldSpaces.TAMRIEL));
		assertEquals(0, WorldSpaces.originX(0));
		assertEquals(0, WorldSpaces.originZ(0));
		assertEquals(WorldSpaces.SPACING, WorldSpaces.originX(1));
		assertEquals(0, WorldSpaces.originZ(1));
		assertEquals(0, WorldSpaces.originX(-1)); // a guest still waiting: the origin
		// Tamriel reaches about 3,800 blocks from Skyrim's (0, 0); nothing of it gets near another space.
		assertTrue(WorldSpaces.HALF > 3 * 3800);
		assertEquals(0, WorldSpaces.SPACING % 16 + WorldSpaces.HALF % 16, "spaces are whole sections apart");
	}

	@Test
	void positionsGoIntoASpaceAndComeBackExactly() {
		// Skyrim's float positions (and blocks, and anything a binary fraction) come back exactly; any
		// double to well under a millionth of a block.
		double[] locals = { 0.0, 0.5, -0.5, -57.25, 98.125, 3800.0, -3800.0, WorldSpaces.HALF - 0.0009765625, -WorldSpaces.HALF, -57.3, 1611.123456 };
		for (int space : new int[] { 0, 1, 2, 7, 8, 9, 100, 4000 }) {
			int ox = WorldSpaces.originX(space), oz = WorldSpaces.originZ(space);
			for (double lx : locals) {
				for (double lz : locals) {
					double x = lx + ox, z = lz + oz;
					boolean exact = lx * 1024 == Math.rint(lx * 1024) && lz * 1024 == Math.rint(lz * 1024);
					assertEquals(space, WorldSpaces.spaceAt(x, z), "space of " + lx + " " + lz + " in " + space);
					assertEquals(lx, WorldSpaces.local(x), exact ? 0.0 : 1e-9);
					assertEquals(lz, WorldSpaces.local(z), exact ? 0.0 : 1e-9);
				}
			}
			float skyrim = -57.3F; // a float from the protocol, moved in and out
			assertEquals(skyrim, (float) WorldSpaces.local(skyrim + (double) ox), 0.0);
			// Taking a place to its world's terms twice changes nothing (SkyLink.pushEvent relies on it), and a
			// portal exit cast to float in the mirror (x + 0.5) comes out exact.
			float exit = (float) (ox + 123.5);
			assertEquals(123.5F, (float) WorldSpaces.local(exit), 0.0);
			assertEquals(123.5F, (float) WorldSpaces.local((float) WorldSpaces.local(exit)), 0.0);
			for (int b : new int[] { 0, 1, -1, -57, 4095, -4096, WorldSpaces.HALF - 1, -WorldSpaces.HALF }) {
				assertEquals(b, WorldSpaces.local(b + ox));
				assertEquals(b, WorldSpaces.local(b + oz));
			}
			for (int s : new int[] { 0, 1, -1, -4, 255, WorldSpaces.HALF / 16 - 1, -WorldSpaces.HALF / 16 }) {
				assertEquals(s, WorldSpaces.localSection(s + ox / 16));
				assertEquals(s, WorldSpaces.localSection(s + oz / 16));
				// A section's blocks are all in the same space as the section.
				assertEquals(WorldSpaces.cellOfSection(s + ox / 16), WorldSpaces.cellOf((s + ox / 16) * 16));
				assertEquals(WorldSpaces.cellOfSection(s + ox / 16), WorldSpaces.cellOf((s + ox / 16) * 16 + 15));
			}
		}
	}

	@Test
	void theEdgeBetweenTwoSpaces() {
		assertEquals(0, WorldSpaces.cellOf(WorldSpaces.HALF - 1));
		assertEquals(1, WorldSpaces.cellOf(WorldSpaces.HALF));
		assertEquals(0, WorldSpaces.cellOf(-WorldSpaces.HALF));
		assertEquals(-1, WorldSpaces.cellOf(-WorldSpaces.HALF - 1));
		assertEquals(0, WorldSpaces.cellOf((double) WorldSpaces.HALF - 1e-6));
		assertEquals(1, WorldSpaces.cellOf((double) WorldSpaces.HALF));
	}

	@Test
	void worldsGetTheNextSpaceOnceAndKeepIt() {
		WorldSpaces t = new WorldSpaces();
		boolean[] changed = { false };
		assertEquals(1, t.allocate(OROTHEIM, "Orotheim", changed));
		assertTrue(changed[0]);
		changed[0] = false;
		assertEquals(2, t.allocate(REDORANS_RETREAT, "", changed));
		assertEquals(1, t.allocate(OROTHEIM, "Somewhere Else", changed));
		assertEquals("Orotheim", t.place(OROTHEIM)); // the first name stays
		changed[0] = false;
		assertEquals(2, t.allocate(REDORANS_RETREAT, "Redoran's Retreat", changed));
		assertTrue(changed[0], "a world's first name is news");
		assertEquals(0, t.allocate(WorldSpaces.TAMRIEL, "Whiterun", null));
		assertEquals(-1, t.spaceOf(WHITERUN));
		assertEquals(OROTHEIM, t.worldOf(1));
		assertEquals(0, t.worldOf(3));
		assertEquals(OROTHEIM, t.worldAt(WorldSpaces.originX(1) - 57.0, WorldSpaces.originZ(1) - 9.0));
		assertEquals(WorldSpaces.TAMRIEL, t.worldAt(1611.0, 364.0));
		assertEquals(0, t.worldAt(WorldSpaces.originX(9), WorldSpaces.originZ(9)));
	}

	@Test
	void theUsersInteriorsLandInSpacesOfTheirOwn() {
		// Dug cells found in the shared space: Orotheim's (-57, 0, -9), Redoran's Retreat's (-14, 10, -57),
		// Tamriel's (1611, -50, 364). After: Tamriel's where it was, the interiors far apart.
		WorldSpaces t = new WorldSpaces();
		int o = t.allocate(OROTHEIM, "Orotheim", null);
		int r = t.allocate(REDORANS_RETREAT, "Redoran's Retreat", null);
		double ox = -57 + WorldSpaces.originX(o), oz = -9 + WorldSpaces.originZ(o);
		double rx = -14 + WorldSpaces.originX(r), rz = -57 + WorldSpaces.originZ(r);
		assertEquals(OROTHEIM, t.worldAt(ox, oz));
		assertEquals(REDORANS_RETREAT, t.worldAt(rx, rz));
		assertTrue(Math.abs(ox - rx) + Math.abs(oz - rz) > WorldSpaces.SPACING / 2.0);
		assertEquals(-57.0, WorldSpaces.local(ox), 0.0);
		assertEquals(-57.0, WorldSpaces.local(rz), 0.0);
		assertEquals(1611.0, WorldSpaces.local(1611.0), 0.0);
	}

	@Test
	void theTableRoundTrips() {
		WorldSpaces t = new WorldSpaces();
		t.allocate(OROTHEIM, "Orotheim", null);
		t.allocate(REDORANS_RETREAT, "Redoran's Retreat", null);
		t.allocate(WHITERUN, "", null);
		t.setMigrated(WorldSpaces.MIGRATION);
		List<String> problems = new ArrayList<>();
		WorldSpaces back = WorldSpaces.parse(t.lines(), problems);
		assertTrue(problems.isEmpty(), problems.toString());
		assertEquals(t.all(), back.all());
		assertEquals("Redoran's Retreat", back.place(REDORANS_RETREAT));
		assertEquals("", back.place(WHITERUN));
		assertEquals(WorldSpaces.MIGRATION, back.migrated());
		assertEquals(4, back.allocate(0x00013814, "", null), "numbering goes on after the last one");
		assertTrue(t.lines().stream().anyMatch(l -> l.startsWith("00015233 1 " + WorldSpaces.SPACING + " 0 Orotheim")), t.lines().toString());
	}

	@Test
	void badLinesAreReportedAndSpacesNeverShared() {
		List<String> problems = new ArrayList<>();
		WorldSpaces t = WorldSpaces.parse(List.of(
			"# comment",
			"0000003C 5 0 0 Tamriel",
			"00015233 0 0 0 Takes Tamriel's",
			"000152C3 3 0 0 Fine",
			"00021594 3 0 0 Same space",
			"000152C3 4 0 0 Same world",
			"nonsense here",
			"0001A26F",
			"migrated 1",
			""), problems);
		assertEquals(6, problems.size(), problems.toString());
		assertEquals(0, t.spaceOf(WorldSpaces.TAMRIEL));
		assertEquals(-1, t.spaceOf(OROTHEIM));
		assertEquals(3, t.spaceOf(REDORANS_RETREAT));
		assertEquals(-1, t.spaceOf(0x00021594));
		assertEquals(1, t.migrated());
		assertEquals(4, t.allocate(OROTHEIM, "", null));
	}

	@Test
	void aGuestsCopyTakesTheHostsSpaces() {
		WorldSpaces copy = new WorldSpaces();
		int before = copy.version();
		Map<Integer, Integer> host = new LinkedHashMap<>();
		host.put(WorldSpaces.TAMRIEL, 0);
		host.put(OROTHEIM, 1);
		host.put(WHITERUN, 5);
		assertTrue(copy.merge(host));
		assertTrue(copy.version() != before);
		assertEquals(5, copy.spaceOf(WHITERUN));
		assertEquals(WHITERUN, copy.worldAt(WorldSpaces.originX(5), WorldSpaces.originZ(5)));
		assertFalse(copy.merge(host), "nothing new the second time");
		Map<Integer, Integer> odd = new LinkedHashMap<>();
		odd.put(REDORANS_RETREAT, 1); // space 1 is Orotheim's: ignored
		assertFalse(copy.merge(odd));
		assertEquals(-1, copy.spaceOf(REDORANS_RETREAT));
	}
}
