package dev.skycraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SafeSpotTest {
	private static final double HALF = 0.3, HEIGHT = 1.8, E = 1e-6;

	/** A world of solid unit cells, for a player 0.6 wide and 1.8 tall. */
	private static final class Cells implements SafeSpot.World {
		final Set<Long> solid = new HashSet<>();

		static long key(int x, int y, int z) {
			return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
		}

		void fill(int x0, int y0, int z0, int x1, int y1, int z1) {
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						this.solid.add(key(x, y, z));
					}
				}
			}
		}

		void clear(int x0, int y0, int z0, int x1, int y1, int z1) {
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						this.solid.remove(key(x, y, z));
					}
				}
			}
		}

		boolean at(int x, int y, int z) {
			return this.solid.contains(key(x, y, z));
		}

		@Override
		public boolean free(double x, double y, double z) {
			for (int cx = (int) Math.floor(x - HALF + E); cx <= (int) Math.floor(x + HALF - E); cx++) {
				for (int cy = (int) Math.floor(y + E); cy <= (int) Math.floor(y + HEIGHT - E); cy++) {
					for (int cz = (int) Math.floor(z - HALF + E); cz <= (int) Math.floor(z + HALF - E); cz++) {
						if (at(cx, cy, cz)) {
							return false;
						}
					}
				}
			}
			return true;
		}

		@Override
		public double ground(double x, double y, double z, double drop) {
			double best = Double.NaN;
			for (int cx = (int) Math.floor(x - HALF + E); cx <= (int) Math.floor(x + HALF - E); cx++) {
				for (int cz = (int) Math.floor(z - HALF + E); cz <= (int) Math.floor(z + HALF - E); cz++) {
					for (int cy = (int) Math.floor(y - drop) - 1; cy <= (int) Math.floor(y); cy++) {
						double top = cy + 1.0;
						if (at(cx, cy, cz) && top <= y + 1e-9 && top >= y - drop && (Double.isNaN(best) || top > best)) {
							best = top;
						}
					}
				}
			}
			return best;
		}
	}

	@Test
	void standingOnTheFloorStaysPut() {
		Cells w = new Cells();
		w.fill(-5, -1, -5, 5, -1, 5);
		assertTrue(SafeSpot.safe(w, 0.5, 0.0, 0.5));
		double[] at = SafeSpot.find(w, 0.5, 0.0, 0.5, 4, 4, 4);
		assertNotNull(at);
		assertEquals(0.5, at[0], 1e-9);
		assertEquals(0.0, at[1], 0.01);
		assertEquals(0.5, at[2], 1e-9);
	}

	@Test
	void buriedInRockClimbsOutOnTop() {
		Cells w = new Cells();
		w.fill(-6, -1, -6, 6, 4, 6); // rock up to y 5, open above
		assertFalse(SafeSpot.safe(w, 0.5, 1.0, 0.5));
		double[] at = SafeSpot.find(w, 0.5, 1.0, 0.5, 3, 8, 2);
		assertNotNull(at);
		assertEquals(0.5, at[0], 1e-9);
		assertEquals(5.0, at[1], 0.01);
		assertEquals(0.5, at[2], 1e-9);
	}

	@Test
	void fallenThroughAShaftIsPutBesideItsTopNotBackInIt() {
		Cells w = new Cells();
		w.fill(-6, -20, -6, 6, -1, 6); // the ground, 20 thick, the void under it
		w.clear(0, -20, 0, 0, -1, 0); // a shaft dug through all of it
		double[] at = SafeSpot.find(w, 0.5, -30.0, 0.5, 4, 32, 0);
		assertNotNull(at);
		assertEquals(0.0, at[1], 0.01, "on the ground's top");
		assertFalse(Math.floor(at[0]) == 0 && Math.floor(at[2]) == 0, "not over the shaft");
		assertTrue(SafeSpot.safe(w, at[0], at[1], at[2]));
	}

	@Test
	void climbingBeatsDroppingAsFar() {
		Cells w = new Cells();
		w.fill(0, 2, 0, 0, 2, 0); // a ledge 3 up (top at y 3)
		w.fill(0, -4, 0, 0, -4, 0); // and one 3 down (top at y -3)
		double[] at = SafeSpot.find(w, 0.5, 0.0, 0.5, 0, 4, 4);
		assertNotNull(at);
		assertEquals(3.0, at[1], 0.01);
	}

	@Test
	void nothingSafeNearGivesNull() {
		Cells w = new Cells();
		w.fill(-6, -6, -6, 6, 6, 6);
		assertNull(SafeSpot.find(w, 0.5, 0.0, 0.5, 3, 3, 3));
	}

	@Test
	void unsupportedSpotIsNotSafe() {
		Cells w = new Cells();
		assertFalse(SafeSpot.safe(w, 0.5, 10.0, 0.5));
	}

	private static SkyTri tri(double... v) {
		float[] f = new float[9];
		for (int i = 0; i < 9; i++) {
			f[i] = (float) v[i];
		}
		return new SkyTri(f, 0, 0);
	}

	@Test
	void triangleThroughTheBoxHitsIt() {
		SkyTri floor = tri(-5, 0.5, -5, 5, 0.5, -5, 0, 0.5, 5);
		assertTrue(SafeSpot.triangleHitsBox(floor, 0, 0, 0, 1, 1, 1));
	}

	@Test
	void triangleUnderTheBoxMissesIt() {
		SkyTri floor = tri(-5, -0.1, -5, 5, -0.1, -5, 0, -0.1, 5);
		assertFalse(SafeSpot.triangleHitsBox(floor, 0, 0, 0, 1, 1, 1));
	}

	@Test
	void slantedTriangleNearACornerMissesIt() {
		// Its bounds overlap the box but the plane x + y + z = 3.2 passes beyond the corner (1, 1, 1).
		SkyTri t = tri(3.2, 0, 0, 0, 3.2, 0, 0, 0, 3.2);
		assertFalse(SafeSpot.triangleHitsBox(t, 0, 0, 0, 1, 1, 1));
		SkyTri u = tri(2.8, 0, 0, 0, 2.8, 0, 0, 0, 2.8);
		assertTrue(SafeSpot.triangleHitsBox(u, 0, 0, 0, 1, 1, 1));
	}

	@Test
	void triangleBesideTheBoxEdgeMissesIt() {
		// Flat at the box's mid height, its bounds overlapping the box, but its near edge (x + z = 2.2)
		// runs past the box's corner (x + z = 2): only that edge's axis separates them.
		SkyTri t = tri(2.2, 0.5, 0, 0, 0.5, 2.2, 3, 0.5, 3);
		assertFalse(SafeSpot.triangleHitsBox(t, 0, 0, 0, 1, 1, 1));
		SkyTri u = tri(1.8, 0.5, 0, 0, 0.5, 1.8, 3, 0.5, 3);
		assertTrue(SafeSpot.triangleHitsBox(u, 0, 0, 0, 1, 1, 1));
	}
}
