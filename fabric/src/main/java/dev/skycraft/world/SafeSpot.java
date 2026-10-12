package dev.skycraft.world;

import org.jspecify.annotations.Nullable;

/**
 * Where to put a player who has to be moved: Skyrim put them somewhere solid (inside a rock, under
 * blocks), or they fell through the ground into the void under it. The nearest spot where the
 * player fits (nothing solid in their box) with something to stand on right under their feet.
 * Pure: the world is asked through {@link World}.
 */
public final class SafeSpot {
	private SafeSpot() {
	}

	/** What the search needs to know about the world around the player. */
	public interface World {
		/** Whether the player's box with its feet at (x, y, z) has nothing solid in it. */
		boolean free(double x, double y, double z);

		/** The top of whatever solid is under feet at (x, y, z), at most {@code drop} below them and not above them, or NaN. */
		double ground(double x, double y, double z, double drop);
	}

	/** Feet this close over the ground stand on it. */
	public static final double STAND = 1.0;
	private static final double LIFT = 0.001;

	/** Whether feet at (x, y, z) fit there and stand on something. */
	public static boolean safe(World world, double x, double y, double z) {
		return world.free(x, y, z) && !Double.isNaN(world.ground(x, y + LIFT, z, STAND));
	}

	/**
	 * The nearest safe feet position to (x, y, z), or null if there's none: columns out to
	 * {@code radius} blocks around (the player's own column first, then block centres), each looked
	 * at from {@code up} blocks above down to {@code down} below. Climbing costs less than dropping
	 * (out of a hole beats further into it).
	 */
	public static double @Nullable [] find(World world, double x, double y, double z, int radius, int up, int down) {
		double[] best = null;
		double bestCost = Double.POSITIVE_INFINITY;
		int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
		for (int r = 0; r <= radius; r++) {
			if (best != null && r - 1.0 > bestCost) {
				break; // every column further out costs more already
			}
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					double cx = r == 0 ? x : bx + dx + 0.5, cz = r == 0 ? z : bz + dz + 0.5;
					double across = Math.hypot(cx - x, cz - z);
					for (int dy = up; dy >= -down; dy--) {
						// The ground within this block: the highest solid top at or below its top.
						double top = by + dy + 1.0;
						double g = world.ground(cx, top, cz, 1.0);
						if (Double.isNaN(g) || g > top + 1e-9) {
							continue;
						}
						double feet = g + LIFT;
						double rise = feet - y;
						double cost = across + (rise >= 0.0 ? rise * 0.5 : -rise * 1.5);
						if (cost >= bestCost || !world.free(cx, feet, cz)) {
							continue;
						}
						best = new double[] { cx, feet, cz };
						bestCost = cost;
					}
				}
			}
		}
		return best;
	}

	/**
	 * Whether a triangle crosses an axis-aligned box (separating axis test, Akenine-Moller): the
	 * box's three axes, the triangle's normal and the nine edge cross products.
	 */
	public static boolean triangleHitsBox(SkyTri t, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		if (t.maxX < minX || t.minX > maxX || t.maxY < minY || t.minY > maxY || t.maxZ < minZ || t.minZ > maxZ) {
			return false;
		}
		double cx = (minX + maxX) * 0.5, cy = (minY + maxY) * 0.5, cz = (minZ + maxZ) * 0.5;
		double hx = (maxX - minX) * 0.5, hy = (maxY - minY) * 0.5, hz = (maxZ - minZ) * 0.5;
		double[] v0 = { t.ax - cx, t.ay - cy, t.az - cz };
		double[] v1 = { t.bx - cx, t.by - cy, t.bz - cz };
		double[] v2 = { t.cx - cx, t.cy - cy, t.cz - cz };
		double[][] e = { { v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2] }, { v2[0] - v1[0], v2[1] - v1[1], v2[2] - v1[2] },
			{ v0[0] - v2[0], v0[1] - v2[1], v0[2] - v2[2] } };
		double[] h = { hx, hy, hz };
		// The nine cross products of the box's axes with the triangle's edges.
		for (double[] edge : e) {
			for (int axis = 0; axis < 3; axis++) {
				double[] a = new double[3];
				a[(axis + 1) % 3] = -edge[(axis + 2) % 3];
				a[(axis + 2) % 3] = edge[(axis + 1) % 3];
				if (separates(a, v0, v1, v2, h)) {
					return false;
				}
			}
		}
		// The triangle's plane.
		double nx = e[0][1] * e[1][2] - e[0][2] * e[1][1];
		double ny = e[0][2] * e[1][0] - e[0][0] * e[1][2];
		double nz = e[0][0] * e[1][1] - e[0][1] * e[1][0];
		double d = nx * v0[0] + ny * v0[1] + nz * v0[2];
		double reach = hx * Math.abs(nx) + hy * Math.abs(ny) + hz * Math.abs(nz);
		return Math.abs(d) <= reach;
	}

	private static boolean separates(double[] a, double[] v0, double[] v1, double[] v2, double[] h) {
		double p0 = a[0] * v0[0] + a[1] * v0[1] + a[2] * v0[2];
		double p1 = a[0] * v1[0] + a[1] * v1[1] + a[2] * v1[2];
		double p2 = a[0] * v2[0] + a[1] * v2[1] + a[2] * v2[2];
		double r = h[0] * Math.abs(a[0]) + h[1] * Math.abs(a[1]) + h[2] * Math.abs(a[2]);
		return Math.min(p0, Math.min(p1, p2)) > r || Math.max(p0, Math.max(p1, p2)) < -r;
	}
}
