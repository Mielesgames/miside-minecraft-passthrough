package dev.skycraft.worlds;

import java.util.Random;

/**
 * Gradient noise the way early terrain code wrote it: coordinates are floored into a 32 bit lattice with an
 * int cast. A cast of a value past 2^31 saturates, and the classic "step down if negative" fix then wraps,
 * so far enough out the lattice cell stops moving and the fraction left over grows without bound. Fed to
 * the fade curve, that unbounded fraction is what turns Home's far edge into the Far Lands.
 *
 * Pure: no Minecraft classes, so the overflow can be unit tested.
 */
public final class ClassicNoise {
	private final int[] perm = new int[512];
	private final double offsetX;
	private final double offsetY;
	private final double offsetZ;

	public ClassicNoise(Random random) {
		// Offsets only on y and z: an x or z offset would move the Far Lands edge with the seed.
		offsetX = 0.0;
		offsetY = random.nextDouble() * 256.0;
		offsetZ = 0.0;
		for (int i = 0; i < 256; i++) {
			perm[i] = i;
		}
		for (int i = 0; i < 256; i++) {
			int j = random.nextInt(256 - i) + i;
			int t = perm[i];
			perm[i] = perm[j];
			perm[j] = t;
			perm[i + 256] = perm[i];
		}
	}

	/**
	 * Floors a coordinate into the int lattice like old code: truncating cast, then one step down for
	 * negatives. Past +2^31 the cast saturates at Integer.MAX_VALUE; past -2^31 it saturates at
	 * Integer.MIN_VALUE and the step down wraps to Integer.MAX_VALUE.
	 */
	public static int lattice(double v) {
		int i = (int) v;
		if (v < i) {
			i--;
		}
		return i;
	}

	/** Distance from the lattice cell: in [0, 1) inside the int range, unbounded once the cell index saturates. */
	public static double fraction(double v) {
		return v - lattice(v);
	}

	public double sample(double x, double y, double z) {
		x += offsetX;
		y += offsetY;
		z += offsetZ;
		int xi = lattice(x);
		int yi = lattice(y);
		int zi = lattice(z);
		double fx = x - xi;
		double fy = y - yi;
		double fz = z - zi;
		int cx = xi & 255;
		int cy = yi & 255;
		int cz = zi & 255;
		double u = fade(fx);
		double v = fade(fy);
		double w = fade(fz);
		int a = perm[cx] + cy;
		int aa = perm[a] + cz;
		int ab = perm[a + 1] + cz;
		int b = perm[cx + 1] + cy;
		int ba = perm[b] + cz;
		int bb = perm[b + 1] + cz;
		return lerp(w,
			lerp(v,
				lerp(u, grad(perm[aa], fx, fy, fz), grad(perm[ba], fx - 1, fy, fz)),
				lerp(u, grad(perm[ab], fx, fy - 1, fz), grad(perm[bb], fx - 1, fy - 1, fz))),
			lerp(v,
				lerp(u, grad(perm[aa + 1], fx, fy, fz - 1), grad(perm[ba + 1], fx - 1, fy, fz - 1)),
				lerp(u, grad(perm[ab + 1], fx, fy - 1, fz - 1), grad(perm[bb + 1], fx - 1, fy - 1, fz - 1))));
	}

	static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}

	/** Dot product with one of 12 cube edge gradients, picked by the hash. */
	static double grad(int hash, double x, double y, double z) {
		int h = hash & 15;
		double u = h < 8 ? x : y;
		double v = h < 4 ? y : (h == 12 || h == 14) ? x : z;
		return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
	}

	/**
	 * Octaves of {@link ClassicNoise}, finest first: octave i runs at frequency 1/2^i and weighs 2^i, so the
	 * coarse octaves shape the land and the finest adds grain. The sum is divided by the total weight, so
	 * normal values stay within [-1, 1]. Only the finest octave reaches 2^31 inside the world, and it is
	 * the one that breaks at the Far Lands.
	 */
	public static final class Octaves {
		private final ClassicNoise[] octaves;
		private final double totalWeight;

		public Octaves(Random random, int count) {
			octaves = new ClassicNoise[count];
			double total = 0;
			for (int i = 0; i < count; i++) {
				octaves[i] = new ClassicNoise(random);
				total += 1 << i;
			}
			totalWeight = total;
		}

		public double sample(double x, double y, double z) {
			double sum = 0;
			double frequency = 1.0;
			double weight = 1.0;
			for (ClassicNoise octave : octaves) {
				sum += octave.sample(x * frequency, y * frequency, z * frequency) * weight;
				frequency *= 0.5;
				weight *= 2.0;
			}
			return sum / totalWeight;
		}

		public int count() {
			return octaves.length;
		}
	}
}
