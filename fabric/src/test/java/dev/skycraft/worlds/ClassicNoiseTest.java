package dev.skycraft.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ClassicNoiseTest {
	private static final double TWO_31 = 2147483648.0;

	@Test
	void latticeFloorsInsideTheIntRange() {
		assertEquals(1, ClassicNoise.lattice(1.5));
		assertEquals(-2, ClassicNoise.lattice(-1.5));
		assertEquals(0, ClassicNoise.lattice(0.0));
		assertEquals(-1, ClassicNoise.lattice(-0.25));
		assertEquals(2147483000, ClassicNoise.lattice(2147483000.75));
		assertEquals(0.75, ClassicNoise.fraction(2147483000.75), 1e-6);
		assertEquals(0.25, ClassicNoise.fraction(-2147483000.75), 1e-6);
	}

	@Test
	void latticeSaturatesPastTwoToThe31() {
		// The int cast saturates at the top...
		assertEquals(Integer.MAX_VALUE, ClassicNoise.lattice(TWO_31 + 1000.0));
		assertTrue(ClassicNoise.fraction(TWO_31 + 1000.0) > 1000.0);
		// ...and at the bottom, where the step down for negatives wraps around to the top.
		assertEquals(Integer.MAX_VALUE, ClassicNoise.lattice(-TWO_31 - 1000.0));
		assertTrue(ClassicNoise.fraction(-TWO_31 - 1000.0) < -4.0e9);
	}

	@Test
	void noiseIsBoundedWhereTheLatticeHolds() {
		ClassicNoise noise = new ClassicNoise(new Random(7));
		Random r = new Random(11);
		for (int i = 0; i < 20000; i++) {
			double v = noise.sample((r.nextDouble() - 0.5) * 2.0e9, r.nextDouble() * 1000.0, (r.nextDouble() - 0.5) * 2.0e9);
			assertTrue(Math.abs(v) <= 1.1, "noise " + v);
		}
	}

	@Test
	void noiseExplodesOnceTheLatticeSaturates() {
		ClassicNoise noise = new ClassicNoise(new Random(7));
		int huge = 0;
		for (int i = 0; i < 64; i++) {
			if (Math.abs(noise.sample(TWO_31 + 500.0 + i * 37.0, i * 3.1, i * 1.7)) > 1.0e6) {
				huge++;
			}
		}
		assertTrue(huge > 48, "only " + huge + " of 64 samples blew up");
	}

	@Test
	void octavesStayNormalized() {
		ClassicNoise.Octaves octaves = new ClassicNoise.Octaves(new Random(3), 16);
		assertEquals(16, octaves.count());
		Random r = new Random(5);
		for (int i = 0; i < 2000; i++) {
			double v = octaves.sample(r.nextDouble() * 1.0e6, r.nextDouble() * 1.0e4, r.nextDouble() * 1.0e6);
			assertTrue(Math.abs(v) <= 1.1, "octaves " + v);
		}
	}

	@Test
	void sameSeedSameNoise() {
		ClassicNoise a = new ClassicNoise(new Random(42));
		ClassicNoise b = new ClassicNoise(new Random(42));
		assertEquals(a.sample(12.3, 4.5, -6.7), b.sample(12.3, 4.5, -6.7), 0.0);
	}
}
