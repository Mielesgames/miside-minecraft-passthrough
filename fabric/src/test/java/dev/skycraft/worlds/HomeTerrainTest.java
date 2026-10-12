package dev.skycraft.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HomeTerrainTest {
	private static final HomeTerrain TERRAIN = new HomeTerrain(HomeTerrain.SEED);
	private static final int EDGE = FarLands.EDGE;

	private static boolean solid(HomeTerrain.Block b) {
		return b != HomeTerrain.Block.AIR && b != HomeTerrain.Block.WATER;
	}

	/** Separate solid layers in a column, counted from the bottom. */
	private static int layers(HomeTerrain.Block[] column) {
		int runs = 0;
		boolean previous = false;
		for (HomeTerrain.Block b : column) {
			boolean s = solid(b);
			if (s && !previous) {
				runs++;
			}
			previous = s;
		}
		return runs;
	}

	private static int top(HomeTerrain.Block[] column) {
		for (int y = column.length - 1; y >= 0; y--) {
			if (solid(column[y])) {
				return y;
			}
		}
		return -1;
	}

	@Test
	void densityIsTameBeforeTheEdgeAndHugePastIt() {
		int huge = 0;
		int samples = 0;
		for (int y = 0; y <= HomeTerrain.TOP; y += 8) {
			assertTrue(Math.abs(TERRAIN.density(EDGE - 1, y, 1000)) < 500, "tame at EDGE - 1, y " + y);
			assertTrue(Math.abs(TERRAIN.density(-(EDGE - 1), y, 1000)) < 500, "tame at -(EDGE - 1), y " + y);
			assertTrue(Math.abs(TERRAIN.density(1000, y, EDGE - 1)) < 500, "tame at z EDGE - 1, y " + y);
			for (double d : new double[] { TERRAIN.density(EDGE, y, 1000), TERRAIN.density(-EDGE, y, 1000), TERRAIN.density(1000, y, EDGE) }) {
				samples++;
				if (Math.abs(d) > 1.0e6) {
					huge++;
				}
			}
		}
		assertTrue(huge >= samples - 2, huge + " of " + samples + " densities blew up past the edge");
	}

	@Test
	void normalLandIsOneLayerAndTheFarLandsAreStacked() {
		double before = 0;
		double after = 0;
		int tall = 0;
		int n = 0;
		for (int i = 0; i < 20; i++) {
			int z = 1000 + i * 3;
			before += layers(TERRAIN.column(EDGE - 1 - i, z));
			HomeTerrain.Block[] far = TERRAIN.column(EDGE + i, z);
			after += layers(far);
			if (top(far) >= 120) {
				tall++;
			}
			n++;
		}
		assertTrue(before / n <= 1.2, "normal land averages " + before / n + " layers");
		assertTrue(after / n >= 3.0, "Far Lands average only " + after / n + " layers");
		assertTrue(tall >= n / 2, "only " + tall + " of " + n + " Far Lands columns tower past y 120");
	}

	@Test
	void campGroundIsLevel() {
		for (int dx = -8; dx <= 30; dx += 2) {
			for (int dz = -6; dz <= 6; dz += 2) {
				assertEquals(SteveCamp.FLOOR_Y, TERRAIN.surfaceY(SteveCamp.ORIGIN_X + dx, SteveCamp.ORIGIN_Z + dz), "camp ground at " + dx + ", " + dz);
			}
		}
	}

	@Test
	void trailRunsGravelToTheWall() {
		for (int x = SteveCamp.ORIGIN_X + 3; x < EDGE; x += 13) {
			HomeTerrain.Block[] column = TERRAIN.column(x, SteveCamp.ORIGIN_Z);
			assertEquals(HomeTerrain.Block.GRAVEL, column[SteveCamp.FLOOR_Y], "trail at " + x);
			assertEquals(SteveCamp.FLOOR_Y, top(column), "trail is open above at " + x);
		}
	}

	@Test
	void crackIsOpenToTheSkyAndTheWallStandsAroundIt() {
		for (int x = EDGE; x < EDGE + FarLands.CRACK_DEPTH; x++) {
			for (int dz = -FarLands.CRACK_HALF_WIDTH; dz <= FarLands.CRACK_HALF_WIDTH; dz++) {
				HomeTerrain.Block[] column = TERRAIN.column(x, SteveCamp.ORIGIN_Z + dz);
				assertEquals(HomeTerrain.Block.GRAVEL, column[SteveCamp.FLOOR_Y], "crack floor at " + x);
				for (int y = SteveCamp.FLOOR_Y + 1; y < HomeTerrain.TOP; y++) {
					assertEquals(HomeTerrain.Block.AIR, column[y], "crack open at " + x + ", " + y);
				}
			}
		}
		// The wall resumes beside the crack and past its end.
		int walled = 0;
		for (int x = EDGE; x < EDGE + FarLands.CRACK_DEPTH; x += 4) {
			if (top(TERRAIN.column(x, SteveCamp.ORIGIN_Z + FarLands.CRACK_HALF_WIDTH + 1)) > SteveCamp.FLOOR_Y + 10) {
				walled++;
			}
		}
		assertTrue(walled >= 6, "wall beside the crack in only " + walled + " of 12 columns");
	}

	@Test
	void bedrockFloorEverywhere() {
		for (int x = -64; x < 64; x += 7) {
			assertEquals(HomeTerrain.Block.BEDROCK, TERRAIN.column(x, x * 3)[0]);
		}
	}

	@Test
	void chunkAndColumnAgree() {
		int cx = Math.floorDiv(SteveCamp.ORIGIN_X, 16) + 3;
		int cz = -2;
		HomeTerrain.Block[] chunk = TERRAIN.chunk(cx, cz);
		for (int lx = 0; lx < 16; lx += 5) {
			for (int lz = 0; lz < 16; lz += 3) {
				HomeTerrain.Block[] column = TERRAIN.column(cx * 16 + lx, cz * 16 + lz);
				for (int y = 0; y < HomeTerrain.TOP; y++) {
					assertEquals(column[y], chunk[(y * 16 + lz) * 16 + lx]);
				}
			}
		}
	}

	@Test
	void seedDecidesTheLand() {
		HomeTerrain again = new HomeTerrain(HomeTerrain.SEED);
		HomeTerrain other = new HomeTerrain(HomeTerrain.SEED + 1);
		int differences = 0;
		for (int x = 0; x < 256; x += 16) {
			assertEquals(TERRAIN.surfaceY(x, 77), again.surfaceY(x, 77));
			if (TERRAIN.surfaceY(x, 77) != other.surfaceY(x, 77)) {
				differences++;
			}
		}
		assertTrue(differences > 4);
	}
}
