package dev.skycraft.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarLandsTest {
	private static final int EDGE = FarLands.EDGE;

	@Test
	void edgeIsTheClassicNumber() {
		assertEquals(12_550_821, EDGE);
		// About 684.412 noise units per 4-block cell, as old terrain used.
		assertEquals(684.412, FarLands.SCALE * 4, 0.01);
	}

	@Test
	void finestOctaveOverflowsExactlyAtTheEdge() {
		double last = (EDGE - 1) * FarLands.SCALE;
		double first = EDGE * FarLands.SCALE;
		assertTrue(last < Integer.MAX_VALUE, "EDGE - 1 must still fit");
		assertTrue(first > 2147483648.0, "EDGE must pass 2^31");
		assertTrue(ClassicNoise.lattice(last) < Integer.MAX_VALUE);
		assertEquals(Integer.MAX_VALUE, ClassicNoise.lattice(first));
		// Same block on the negative side.
		assertTrue(ClassicNoise.lattice(-last) < 0);
		assertEquals(Integer.MAX_VALUE, ClassicNoise.lattice(-first));
	}

	@Test
	void fractionBlowsUpOnlyPastTheEdge() {
		for (int x : new int[] { 0, 1_000_000, EDGE - 1000, EDGE - 1, -(EDGE - 1) }) {
			double f = ClassicNoise.fraction(x * FarLands.SCALE);
			assertTrue(f >= 0 && f < 1, x + " -> " + f);
		}
		for (int x : new int[] { EDGE, EDGE + 1, -EDGE, -EDGE - 1 }) {
			assertTrue(Math.abs(ClassicNoise.fraction(x * FarLands.SCALE)) > 50, "no overflow at " + x);
		}
	}

	@Test
	void isFarOnBothAxesAndSigns() {
		assertFalse(FarLands.isFar(EDGE - 1, 0));
		assertTrue(FarLands.isFar(EDGE, 0));
		assertFalse(FarLands.isFar(-(EDGE - 1), 0));
		assertTrue(FarLands.isFar(-EDGE, 0));
		assertTrue(FarLands.isFar(0, EDGE));
		assertTrue(FarLands.isFar(0, -EDGE));
		assertFalse(FarLands.isFar(EDGE - 1, -(EDGE - 1)));
		assertTrue(FarLands.isFar(Integer.MIN_VALUE, 0));
	}

	@Test
	void farLandsFitInsideTheWorldBorder() {
		// 26.3's default border reaches 29,999,984 blocks out.
		assertTrue(EDGE + FarLands.CRACK_DEPTH < 29_999_984);
	}

	@Test
	void crackOpensStraightOutFromTheCamp() {
		int y = SteveCamp.FLOOR_Y + 1;
		assertTrue(FarLands.inCrack(EDGE, y, SteveCamp.ORIGIN_Z));
		assertTrue(FarLands.inCrack(EDGE + FarLands.CRACK_DEPTH - 1, y + 40, SteveCamp.ORIGIN_Z + FarLands.CRACK_HALF_WIDTH));
		assertFalse(FarLands.inCrack(EDGE - 1, y, SteveCamp.ORIGIN_Z));
		assertFalse(FarLands.inCrack(EDGE + FarLands.CRACK_DEPTH, y, SteveCamp.ORIGIN_Z));
		assertFalse(FarLands.inCrack(EDGE, y, SteveCamp.ORIGIN_Z + FarLands.CRACK_HALF_WIDTH + 1));
		assertFalse(FarLands.inCrack(EDGE, SteveCamp.FLOOR_Y, SteveCamp.ORIGIN_Z));
	}
}
