package dev.skycraft.world;

import static dev.skycraft.link.Proto.DIG_BEDROCK;
import static dev.skycraft.link.Proto.DIG_DIRT;
import static dev.skycraft.link.Proto.DIG_GRASS;
import static dev.skycraft.link.Proto.DIG_STONE;
import static dev.skycraft.link.Proto.TRI_DIGGABLE;
import static dev.skycraft.link.Proto.TRI_MATERIAL_SHIFT;
import static dev.skycraft.link.Proto.TRI_TERRAIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SkyGroundTest {
	private static final int LAND = TRI_TERRAIN | TRI_DIGGABLE | (DIG_GRASS << TRI_MATERIAL_SHIFT);
	private static final int ROCK = TRI_DIGGABLE | (DIG_STONE << TRI_MATERIAL_SHIFT);

	private static SkyGround.Surfaces source(List<SkyTri> all) {
		return (x0, y0, z0, x1, y1, z1, out) -> {
			for (SkyTri t : all) {
				if (t.maxX >= x0 && t.minX <= x1 && t.maxY >= y0 && t.minY <= y1 && t.maxZ >= z0 && t.minZ <= z1) {
					out.add(t);
				}
			}
		};
	}

	/** One triangle, wound so its normal faces along (nx, ny, nz). */
	private static void tri(List<SkyTri> out, int flags, double[] a, double[] b, double[] c, double nx, double ny, double nz) {
		double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
		double wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
		double cx = uy * wz - uz * wy, cy = uz * wx - ux * wz, cz = ux * wy - uy * wx;
		double[] second = b, third = c;
		if (cx * nx + cy * ny + cz * nz < 0.0) {
			second = c;
			third = b;
		}
		float[] v = { (float) a[0], (float) a[1], (float) a[2], (float) second[0], (float) second[1], (float) second[2], (float) third[0], (float) third[1],
			(float) third[2] };
		out.add(new SkyTri(v, 0, flags));
	}

	/** A quad (corners in order round it) as two triangles facing along (nx, ny, nz). */
	private static void quad(List<SkyTri> out, int flags, double[] a, double[] b, double[] c, double[] d, double nx, double ny, double nz) {
		tri(out, flags, a, b, c, nx, ny, nz);
		tri(out, flags, a, c, d, nx, ny, nz);
	}

	/** Flat land at height y over [x0, x1] x [z0, z1], in 2-block pieces like Skyrim's. */
	private static void land(List<SkyTri> out, double y, int x0, int z0, int x1, int z1) {
		for (int x = x0; x < x1; x += 2) {
			for (int z = z0; z < z1; z += 2) {
				quad(out, LAND, new double[] { x, y, z }, new double[] { x + 2, y, z }, new double[] { x + 2, y, z + 2 }, new double[] { x, y, z + 2 }, 0, 1, 0);
			}
		}
	}

	/** A box's six faces, facing out of it (a rock) or into it (a room). */
	private static void box(List<SkyTri> out, int flags, boolean outward, double x0, double y0, double z0, double x1, double y1, double z1) {
		double s = outward ? 1.0 : -1.0;
		quad(out, flags, new double[] { x0, y0, z0 }, new double[] { x1, y0, z0 }, new double[] { x1, y0, z1 }, new double[] { x0, y0, z1 }, 0, -s, 0);
		quad(out, flags, new double[] { x0, y1, z0 }, new double[] { x1, y1, z0 }, new double[] { x1, y1, z1 }, new double[] { x0, y1, z1 }, 0, s, 0);
		quad(out, flags, new double[] { x0, y0, z0 }, new double[] { x0, y1, z0 }, new double[] { x0, y1, z1 }, new double[] { x0, y0, z1 }, -s, 0, 0);
		quad(out, flags, new double[] { x1, y0, z0 }, new double[] { x1, y1, z0 }, new double[] { x1, y1, z1 }, new double[] { x1, y0, z1 }, s, 0, 0);
		quad(out, flags, new double[] { x0, y0, z0 }, new double[] { x1, y0, z0 }, new double[] { x1, y1, z0 }, new double[] { x0, y1, z0 }, 0, 0, -s);
		quad(out, flags, new double[] { x0, y0, z1 }, new double[] { x1, y0, z1 }, new double[] { x1, y1, z1 }, new double[] { x0, y1, z1 }, 0, 0, s);
	}

	private static int classify(List<SkyTri> world, int x, int y, int z) {
		return SkyGround.classify(source(world), x, y, z);
	}

	@Test
	void underTheLandIsDirtThenStoneThenBedrock() {
		List<SkyTri> w = new ArrayList<>();
		land(w, 0.0, -40, -40, 40, 40);
		assertEquals(DIG_DIRT, SkyGround.base(classify(w, 0, -3, 0)));
		assertEquals(DIG_STONE, SkyGround.base(classify(w, 0, -30, 0)));
		assertEquals(DIG_STONE, SkyGround.base(classify(w, 0, -64, 0)), "63.5 under the land");
		assertEquals(DIG_BEDROCK, classify(w, 0, -65, 0), "64.5 under the land");
		assertEquals(DIG_BEDROCK, classify(w, 0, -90, 0));
	}

	@Test
	void stoneCarriesItsDepthForOres() {
		List<SkyTri> w = new ArrayList<>();
		land(w, 0.0, -40, -40, 40, 40);
		int m = classify(w, 0, -41, 0);
		assertEquals(DIG_STONE, SkyGround.base(m));
		assertEquals(40, (m >>> SkyGround.DEPTH_SHIFT & 0xFF) - 1, "40.5 under the land, in whole blocks");
	}

	@Test
	void openAirAboveTheLandStaysOpen() {
		List<SkyTri> w = new ArrayList<>();
		land(w, 0.0, -40, -40, 40, 40);
		box(w, ROCK, true, 2, -2, -2, 5, 3, 2); // a rock beside it
		assertEquals(SkyGround.AIR, classify(w, 0, 0, 0));
		assertEquals(SkyGround.AIR, classify(w, 0, 5, 0));
	}

	/**
	 * The fall through the world near Orotheim: a rock (an ore vein, a cliff, a cave mouth) sunk into
	 * the land has an underside. More than the land reach under the land, the cells under that
	 * underside used to be called open (the nearest surface straight up faces down), so they were
	 * never revealed and the player fell through them into the void.
	 */
	@Test
	void groundUnderABuriedRocksUndersideIsStillGround() {
		List<SkyTri> w = new ArrayList<>();
		land(w, 0.0, -40, -40, 40, 40);
		box(w, ROCK, true, -3, -12, -3, 3, 3, 3);
		for (int y = -13; y >= -40; y--) {
			int m = classify(w, 0, y, 0);
			assertTrue(m > SkyGround.AIR, "inside at y " + y + ", got " + m);
		}
		assertEquals(DIG_BEDROCK, classify(w, 0, -66, 0));
	}

	@Test
	void probeSeesTheLandFarAboveForWallsToo() {
		List<SkyTri> w = new ArrayList<>();
		land(w, 0.0, -40, -40, 40, 40);
		box(w, ROCK, true, -3, -12, -3, 3, 3, 3);
		SkyGround.Probe probe = new SkyGround.Probe(source(w)).around(0, -30, 0, 1, -29, 1);
		assertTrue(probe.land.isEmpty(), "the land is out of the near reach");
		assertEquals(0.0, probe.landOver(0.5, 0.5), 1e-6);
		assertTrue(probe.test(0.5, -29.5, 0.5) > SkyGround.AIR);
		assertEquals(29.5, probe.depth, 1e-6);
	}

	/**
	 * Digging out of an interior: a room's floor and ceiling run on past its walls (kit pieces
	 * overlap). Out past a wall, between those overhangs, the nearest surface is the floor or the
	 * ceiling seen from the room's side, so it used to look open: a hole into the void.
	 */
	@Test
	void rockOutPastAnInteriorsWallIsSolid() {
		List<SkyTri> w = new ArrayList<>();
		box(w, ROCK, false, 0, 0, 0, 8, 4, 8); // the room, faces inward
		quad(w, ROCK, new double[] { -6, 0, -6 }, new double[] { 14, 0, -6 }, new double[] { 14, 0, 14 }, new double[] { -6, 0, 14 }, 0, 1, 0); // floor overhang
		quad(w, ROCK, new double[] { -6, 4, -6 }, new double[] { 14, 4, -6 }, new double[] { 14, 4, 14 }, new double[] { -6, 4, 14 }, 0, -1, 0); // ceiling overhang
		assertTrue(classify(w, -4, 1, 3) > SkyGround.AIR, "out past the wall");
		assertTrue(classify(w, -4, 2, 5) > SkyGround.AIR, "out past the wall");
		assertEquals(SkyGround.AIR, classify(w, 4, 1, 4), "in the room");
		assertEquals(SkyGround.AIR, classify(w, 1, 2, 6), "in the room by a wall");
	}

	@Test
	void underAnInteriorsCeilingWithNothingBelowIsRock() {
		List<SkyTri> w = new ArrayList<>();
		quad(w, ROCK, new double[] { -10, 10, -10 }, new double[] { 10, 10, -10 }, new double[] { 10, 10, 10 }, new double[] { -10, 10, 10 }, 0, -1, 0);
		assertTrue(classify(w, 0, 5, 0) > SkyGround.AIR);
	}

	@Test
	void interiorBedrockIsUnderTheFloorAbove() {
		List<SkyTri> w = new ArrayList<>();
		box(w, ROCK, false, 0, 0, 0, 8, 4, 8);
		assertEquals(DIG_STONE, SkyGround.base(classify(w, 4, -10, 4)));
		assertEquals(DIG_BEDROCK, classify(w, 4, -70, 4));
	}

	@Test
	void theBottomOfTheWorldIsBedrock() {
		List<SkyTri> w = new ArrayList<>();
		assertEquals(DIG_BEDROCK, classify(w, 0, SkyGround.floorY - 4, 0));
		assertEquals(DIG_STONE, SkyGround.base(classify(w, 0, 0, 0)), "nothing anywhere: rock");
	}

	@Test
	void openInTheRoomButBehindAWallIsSolidForDigging() {
		List<SkyTri> w = new ArrayList<>();
		box(w, ROCK, false, 0, 0, 0, 8, 4, 8);
		quad(w, ROCK, new double[] { -6, 0, -6 }, new double[] { 14, 0, -6 }, new double[] { 14, 0, 14 }, new double[] { -6, 0, 14 }, 0, 1, 0);
		quad(w, ROCK, new double[] { -6, 4, -6 }, new double[] { 14, 4, -6 }, new double[] { 14, 4, 14 }, new double[] { -6, 4, 14 }, 0, -1, 0);
		SkyGround.Probe probe = new SkyGround.Probe(source(w)).around(-4, 1, 3, -3, 2, 4);
		assertEquals(SkyGround.AIR, probe.test(-3.5, 1.5, 3.5), "the plain test sees the floor's open side");
		int solid = probe.testSolid(-3.5, 1.5, 3.5);
		assertTrue(solid > SkyGround.AIR, "sideways it's behind the room's wall");
		assertNotNull(probe.surface);
		assertEquals(3.5, probe.depth, 1e-6);
	}
}
