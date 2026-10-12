package dev.skycraft.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.skycraft.worlds.FarLands;
import dev.skycraft.worlds.SteveCamp;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** A built portal stands behind its arrival spot, facing the way the arrival faces, in one piece. */
class PortalPlanTest {
	@Test
	void theExitIsTheArrivalSpotFacingTheArrivalsWay() {
		// Facing south, west, north, east: the exit is on the spot (half a block over along the portal), same yaw.
		check(PortalPlan.behind(10, 70, 20, 0.0f), 10.0, 70.0, 20.5, 0.0);
		check(PortalPlan.behind(10, 70, 20, 90.0f), 10.5, 70.0, 20.0, 90.0);
		check(PortalPlan.behind(10, 70, 20, 180.0f), 10.0, 70.0, 20.5, 180.0);
		check(PortalPlan.behind(10, 70, 20, -90.0f), 10.5, 70.0, 20.0, -90.0);
		check(PortalPlan.behind(10, 70, 20, 270.0f), 10.5, 70.0, 20.0, -90.0);
		check(PortalPlan.behind(10, 70, 20, -170.0f), 10.0, 70.0, 20.5, 180.0);
		// Tamriel Wilds: in front of the Dwemer tower, facing north at it; the portal is just south.
		PortalPlan wilds = PortalPlan.behind(91, 72, 306, 180.0f);
		assertEquals(307, wilds.z());
		assertEquals(PortalStore.AXIS_X, wilds.axis());
	}

	private static void check(PortalPlan plan, double x, double y, double z, double yaw) {
		double[] e = plan.exit();
		assertEquals(x, e[0], 1.0E-9);
		assertEquals(y, e[1], 1.0E-9);
		assertEquals(z, e[2], 1.0E-9);
		assertEquals(yaw, e[3], 1.0E-9);
		// The exit stands on the new floor.
		int fx = (int) Math.floor(x), fz = (int) Math.floor(z);
		assertTrue(plan.floor().stream().anyMatch(p -> p[0] == fx && p[1] == (int) y - 1 && p[2] == fz), "no floor under the exit");
	}

	@Test
	void framePortalApronAndShellDontOverlap() {
		PortalPlan plan = PortalPlan.behind(0, 64, 0, 0.0f);
		assertEquals(6, plan.portal().size());
		assertEquals(14, plan.frame().size());
		assertEquals(24, plan.clear().size());
		assertEquals(8, plan.floor().size());
		Set<String> seen = new HashSet<>();
		for (List<int[]> part : List.of(plan.portal(), plan.frame(), plan.clear(), plan.floor(), plan.shell())) {
			for (int[] p : part) {
				assertTrue(seen.add(p[0] + "," + p[1] + "," + p[2]), "twice: " + p[0] + "," + p[1] + "," + p[2]);
			}
		}
		// The shell is the box one block round everything: 6 wide, 7 high, 5 deep.
		assertEquals(6 * 7 * 5, seen.size());
		// The frame's bottom row is under the portal, the floor under the apron at the same height.
		int[] low = plan.at(0, 0, -1);
		assertTrue(plan.frame().stream().anyMatch(p -> p[0] == low[0] && p[1] == low[1] && p[2] == low[2]));
		assertTrue(plan.floor().stream().allMatch(p -> p[1] == 63));
		int[] b = plan.bounds();
		assertEquals(62, b[1]);
		assertEquals(68, b[4]);
	}

	@Test
	void furtherPortalsStandBesideTheFirst() {
		PortalStore s = new PortalStore();
		PortalPlan first = PortalPlan.behind(0, 70, 0, 0.0f);
		assertTrue(first.freeIn(s, PortalKeys.NETHER));
		s.addIn(PortalKeys.NETHER, first.x(), first.y(), first.z(), first.axis(), PortalPlan.WIDTH, PortalPlan.HEIGHT, first.side(), "The Nether");
		assertFalse(first.freeIn(s, PortalKeys.NETHER));
		assertTrue(first.freeIn(s, PortalKeys.TAMRIEL_WILDS));
		assertTrue(first.freeIn(s, PortalStore.MIRROR));
		List<PortalPlan> sites = first.sites(5);
		assertEquals(first, sites.get(0));
		assertEquals(first.x() + 5, sites.get(1).x());
		assertEquals(first.x() - 5, sites.get(2).x());
		assertTrue(sites.get(1).freeIn(s, PortalKeys.NETHER));
		assertTrue(sites.get(2).freeIn(s, PortalKeys.NETHER));
	}

	@Test
	void theCracksPortalFacesTheCamp() {
		PortalPlan crack = Ending.seamPortal();
		double[] e = crack.exit();
		assertEquals(FarLands.EDGE + 0.5, e[0], 1.0E-9);
		assertEquals(SteveCamp.FLOOR_Y + 1, e[1], 1.0E-9);
		assertEquals(90.0, e[3], 1.0E-9); // west, back toward the camp
		assertEquals(FarLands.EDGE + 1, crack.x());
		// Its portal blocks are in the crack, above the camp's ground.
		for (int[] p : crack.portal()) {
			assertTrue(FarLands.inCrack(p[0], p[1], p[2]), p[0] + " " + p[1] + " " + p[2]);
		}
	}
}
