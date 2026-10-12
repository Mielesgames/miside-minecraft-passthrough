package dev.skycraft.world;

import static dev.skycraft.link.Proto.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What's inside Skyrim's geometry, worked out from its surfaces alone (pure: the surfaces come from
 * a {@link Surfaces} source, SkyCollision in the game, so this is unit tested). The rules, in order:
 * <ol>
 * <li>Under the land (Skyrim's terrain, a height field) is inside, however deep and whatever is
 * buried there. Rocks, cliffs, ore veins and cave mouths sunk into the ground have undersides, but
 * the ground under them is still ground. The land is looked for far up ({@link #DEEP_REACH}), not
 * only near the point.</li>
 * <li>Near a surface (within {@link #SEARCH}), the nearest one decides: behind it is inside
 * (surfaces face out of their solid side).</li>
 * <li>Far from every surface, straight up and down decide: under the top of something, or over the
 * underside of something, is inside. It's open only with a floor (or the land) under it and a
 * ceiling (or the sky) over it; with nothing at all under it (past the bottom of an interior) it's
 * rock.</li>
 * <li>For digging ({@link Probe#testSolid}) a point that seems open is checked sideways as well: if
 * the first surface a level ray from it meets is seen from behind, the point is behind a wall (out
 * past an interior's rooms, or inside a rock that another surface crosses): inside.</li>
 * </ol>
 * Bedrock is {@link #BEDROCK_DEPTH} under the land (in interiors, under the nearest floor above),
 * and everywhere at or below {@link #floorY}.
 */
public final class SkyGround {
	private SkyGround() {
	}

	/** Leave the cell alone: Skyrim geometry that can't be dug (a building) is there. */
	public static final int KEEP = -1;
	/** Open air, or only partly inside Skyrim's geometry (its surface stays; walls are drawn). */
	public static final int AIR = 0;
	/** Blocks this far under the land's surface are bedrock. */
	public static final double BEDROCK_DEPTH = 64.0;
	/** Below this depth, stone is deepslate (with deepslate ores). */
	public static final double DEEPSLATE_DEPTH = 40.0;
	/**
	 * A stone material from classify() can carry how deep it is under the land, in whole blocks + 1,
	 * in bits 8-15 (0 = not known), so ores can depend on depth. {@link #base} strips it.
	 */
	public static final int DEPTH_SHIFT = 8;
	/** A cell becomes a block only when this many of its 27 sample points are inside. */
	static final int WHOLE = 25;
	private static final int SAMPLES = 27;

	static final double SEARCH = 2.5;
	/** How far above a probe's box the land is gathered exactly (the walls of holes are cut along it). */
	public static final double LAND_REACH = 16.0;
	/** How far above a point the land is looked for at all: deeper than this under it is past bedrock. */
	public static final double DEEP_REACH = BEDROCK_DEPTH + 32.0;
	/** How far up and down a point far from every surface looks. */
	public static final double COLUMN_REACH = 96.0;
	/** How far sideways {@link Probe#testSolid} looks for the back of a wall. */
	public static final double SIDE_REACH = 8.0;
	private static final double[] SAMPLE = { 1.0 / 6.0, 0.5, 5.0 / 6.0 };

	/**
	 * Cells at or below this height are bedrock whatever is over them: the bottom of the mirror
	 * world (set from its dimension), so even a dig far from any of Skyrim's geometry ends.
	 */
	public static volatile int floorY = -1016;

	/** Where Skyrim's surfaces come from. */
	@FunctionalInterface
	public interface Surfaces {
		/** Adds every surface (as it was before anything was dug out of it) whose bounds overlap the box. */
		void near(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<SkyTri> out);
	}

	/** Is a point inside Skyrim's geometry? See the class comment for the rules. */
	public static class Probe {
		/** The land (terrain) near the box: a height field, so "below it" is "inside". */
		public final List<SkyTri> land = new ArrayList<>();
		/** Everything else (rocks, cliffs, cave walls, trees, buildings) near the box. */
		public final List<SkyTri> tris = new ArrayList<>();
		/** After {@link #test}: the surface deciding it (null: none near, deep inside). */
		public @Nullable SkyTri surface;
		/** After {@link #test}: how far behind that surface (infinite if none near). */
		public double depth;
		private final Surfaces source;
		private final List<SkyTri> gathered = new ArrayList<>();
		private final List<SkyTri> line = new ArrayList<>();
		private final double[] q = new double[3];
		private double boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ;
		// The land far over the box (gathered only when a point is deeper under it than the land
		// reach), surfaces far over and under it (only when a point is far from every surface), that
		// by block column, and per block the surfaces that could be within SEARCH of a point in it.
		private @Nullable List<SkyTri> deep;
		private @Nullable List<SkyTri> column;
		private final Map<Long, List<SkyTri>> columns = new HashMap<>();
		private final Map<Long, List<SkyTri>> nearCells = new HashMap<>();

		public Probe(Surfaces source) {
			this.source = source;
		}

		/** Gathers the surfaces around a box (MC coords) once, for many tests inside it. */
		public Probe around(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			this.gathered.clear();
			this.land.clear();
			this.tris.clear();
			this.deep = null;
			this.column = null;
			this.columns.clear();
			this.nearCells.clear();
			this.boxMinX = minX;
			this.boxMinY = minY;
			this.boxMinZ = minZ;
			this.boxMaxX = maxX;
			this.boxMaxY = maxY;
			this.boxMaxZ = maxZ;
			this.source.near(minX - SEARCH, minY - SEARCH, minZ - SEARCH, maxX + SEARCH, maxY + SEARCH + LAND_REACH, maxZ + SEARCH, this.gathered);
			for (SkyTri t : this.gathered) {
				if (t.stairHelper) {
					continue;
				}
				if (t.terrain) {
					this.land.add(t);
				} else if (t.maxY >= minY - SEARCH && t.minY <= maxY + SEARCH) {
					this.tris.add(t);
				}
			}
			return this;
		}

		/** The land's height at (x, z) near the box, or NaN where there's none near. */
		public double landHeight(double x, double z) {
			return highest(this.land, x, z);
		}

		private double highest(List<SkyTri> from, double x, double z) {
			double best = Double.NaN;
			for (SkyTri t : from) {
				double h = t.heightAt(x, z);
				if (!Double.isNaN(h) && (Double.isNaN(best) || h > best)) {
					best = h;
					this.surface = t;
				}
			}
			return best;
		}

		/** The land's height at (x, z): near the box, else far over it (a point deep under it), or NaN. */
		public double landOver(double x, double z) {
			double h = landHeight(x, z);
			return Double.isNaN(h) ? deepLandHeight(x, z) : h;
		}

		/** The land's height at (x, z) looked for far over the box, or NaN. */
		private double deepLandHeight(double x, double z) {
			List<SkyTri> d = this.deep;
			if (d == null) {
				d = new ArrayList<>();
				this.gathered.clear();
				this.source.near(this.boxMinX - SEARCH, this.boxMinY - SEARCH, this.boxMinZ - SEARCH, this.boxMaxX + SEARCH, this.boxMaxY + DEEP_REACH, this.boxMaxZ + SEARCH,
					this.gathered);
				for (SkyTri t : this.gathered) {
					if (t.terrain && !t.stairHelper) {
						d.add(t);
					}
				}
				this.deep = d;
			}
			return highest(d, x, z);
		}

		/** KEEP (inside something that stays), AIR (in front of a surface) or a material (inside). */
		public int test(double px, double py, double pz) {
			// Under the land: inside, whatever else is buried there (rocks, roots, the underside of a
			// cliff or a cave mouth).
			this.surface = null;
			double ground = landOver(px, pz);
			if (!Double.isNaN(ground) && py < ground && this.surface != null) {
				this.depth = ground - py;
				return materialOf(this.surface);
			}
			// Above the land (or where there's none, as in interiors): inside an object if behind its
			// nearest surface (surfaces face out of their solid side).
			SkyTri nearest = null;
			double best = SEARCH * SEARCH;
			double bx = 0, by = 0, bz = 0;
			for (SkyTri t : nearCell(px, py, pz)) {
				// Its box already farther than the best so far: it can't be nearer.
				double ex = Math.max(0.0, Math.max(t.minX - px, px - t.maxX));
				double ey = Math.max(0.0, Math.max(t.minY - py, py - t.maxY));
				double ez = Math.max(0.0, Math.max(t.minZ - pz, pz - t.maxZ));
				if (ex * ex + ey * ey + ez * ez >= best) {
					continue;
				}
				closestPoint(t, px, py, pz, this.q);
				double dx = px - this.q[0], dy = py - this.q[1], dz = pz - this.q[2];
				double d2 = dx * dx + dy * dy + dz * dz;
				if (d2 < best) {
					best = d2;
					nearest = t;
					bx = this.q[0];
					by = this.q[1];
					bz = this.q[2];
				}
			}
			this.surface = nearest;
			if (nearest == null) {
				this.depth = Double.POSITIVE_INFINITY;
				return farFromSurfaces(px, py, pz);
			}
			if ((px - bx) * nearest.nx + (py - by) * nearest.ny + (pz - bz) * nearest.nz >= 0.0) {
				return AIR;
			}
			if (!nearest.diggable) {
				return KEEP;
			}
			this.depth = Math.sqrt(best);
			return materialOf(nearest);
		}

		/**
		 * As {@link #test}, for digging, where calling rock open leaves a hole into nothing: a point
		 * that seems open is also checked sideways. If the first surface a level ray from it meets
		 * (within {@link #SIDE_REACH}) is seen from behind, the point is behind a wall: inside.
		 */
		public int testSolid(double px, double py, double pz) {
			int r = test(px, py, pz);
			return r == AIR ? behindWall(px, py, pz) : r;
		}

		private int behindWall(double px, double py, double pz) {
			for (int axis = 0; axis < 2; axis++) {
				this.line.clear();
				if (axis == 0) {
					this.source.near(px - SIDE_REACH, py - 0.01, pz - 0.01, px + SIDE_REACH, py + 0.01, pz + 0.01, this.line);
				} else {
					this.source.near(px - 0.01, py - 0.01, pz - SIDE_REACH, px + 0.01, py + 0.01, pz + SIDE_REACH, this.line);
				}
				for (int sign = -1; sign <= 1; sign += 2) {
					double ux = axis == 0 ? sign : 0.0, uz = axis == 1 ? sign : 0.0;
					SkyTri first = null;
					double best = Double.POSITIVE_INFINITY;
					for (SkyTri t : this.line) {
						// Invisible stair ramps aren't walls; surfaces the ray only grazes say nothing.
						if (t.stairHelper || Math.abs(t.nx * ux + t.nz * uz) < 0.05) {
							continue;
						}
						double s = SkyRay.intersect(t, px, py, pz, ux * SIDE_REACH, 0.0, uz * SIDE_REACH);
						if (s >= 0.0 && s <= 1.0 && s < best) {
							best = s;
							first = t;
						}
					}
					if (first != null && first.nx * ux + first.nz * uz > 0.0) {
						this.surface = first;
						this.depth = best * SIDE_REACH;
						return first.diggable ? materialOf(first) : KEEP;
					}
				}
			}
			return AIR;
		}

		/**
		 * A point with no surface near (open sky, the middle of a big room, deep in rock): straight
		 * up and down decide. Under the top of something (a surface facing up) or over the underside
		 * of something (a surface facing down) it's inside that. Otherwise it's open if there's a
		 * floor (or the land) under it, with a ceiling or the sky over it; with nothing under it at
		 * all (past the bottom of an interior's geometry) it's rock.
		 */
		private int farFromSurfaces(double px, double py, double pz) {
			SkyTri above = null, below = null;
			double aboveY = Double.POSITIVE_INFINITY, belowY = Double.NEGATIVE_INFINITY;
			for (SkyTri t : columnAt(px, pz)) {
				double h = t.heightAt(px, pz);
				if (Double.isNaN(h)) {
					continue;
				}
				if (h > py) {
					if (h < aboveY) {
						aboveY = h;
						above = t;
					}
				} else if (h > belowY) {
					belowY = h;
					below = t;
				}
			}
			if (above != null && above.ny > 0.0) {
				this.surface = above;
				this.depth = aboveY - py;
				return above.diggable ? materialOf(above) : KEEP;
			}
			if (below != null && below.ny < 0.0) {
				this.surface = below;
				this.depth = py - belowY;
				return below.diggable ? materialOf(below) : KEEP;
			}
			if (below == null) {
				return DIG_STONE;
			}
			return AIR;
		}

		private List<SkyTri> nearCell(double px, double py, double pz) {
			int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
			long key = cellKey(cx, cy, cz);
			List<SkyTri> list = this.nearCells.get(key);
			if (list == null) {
				list = new ArrayList<>();
				for (SkyTri t : this.tris) {
					if (t.maxX >= cx - SEARCH && t.minX <= cx + 1 + SEARCH && t.maxY >= cy - SEARCH && t.minY <= cy + 1 + SEARCH && t.maxZ >= cz - SEARCH
						&& t.minZ <= cz + 1 + SEARCH) {
						list.add(t);
					}
				}
				this.nearCells.put(key, list);
			}
			return list;
		}

		/** The surfaces over or under the block column holding (x, z). */
		private List<SkyTri> columnAt(double x, double z) {
			if (this.column == null) {
				this.gathered.clear();
				this.source.near(Math.min(x, this.boxMinX), this.boxMinY - COLUMN_REACH, Math.min(z, this.boxMinZ), Math.max(x, this.boxMaxX),
					this.boxMaxY + COLUMN_REACH, Math.max(z, this.boxMaxZ), this.gathered);
				this.column = new ArrayList<>();
				for (SkyTri t : this.gathered) {
					if (!t.stairHelper && Math.abs(t.ny) >= 0.05) {
						this.column.add(t);
					}
				}
			}
			int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
			long key = ((long) bx << 32) | (bz & 0xFFFFFFFFL);
			List<SkyTri> list = this.columns.get(key);
			if (list == null) {
				list = new ArrayList<>();
				for (SkyTri t : this.column) {
					if (t.maxX >= bx && t.minX <= bx + 1 && t.maxZ >= bz && t.minZ <= bz + 1) {
						list.add(t);
					}
				}
				this.columns.put(key, list);
			}
			return list;
		}
	}

	private static long cellKey(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
	}

	static int materialOf(SkyTri t) {
		return t.material == DIG_NONE ? DIG_STONE : t.material;
	}

	/**
	 * Is this cell wholly inside Skyrim's geometry, and if so what block is it (a Proto.DIG_*
	 * material)? Cells only partly inside stay Skyrim's (AIR): its surface stays, and the part under
	 * it shows as Minecraft walls wherever it's been dug next to (see the client's DigWalls).
	 */
	public static int classify(Surfaces surfaces, int x, int y, int z) {
		Probe probe = new Probe(surfaces).around(x, y, z, x + 1, y + 1, z + 1);
		int open = 0;
		int centreMaterial = DIG_STONE;
		double centreDepth = Double.POSITIVE_INFINITY;
		for (double sy : SAMPLE) {
			for (double sz : SAMPLE) {
				for (double sx : SAMPLE) {
					int r = probe.testSolid(x + sx, y + sy, z + sz);
					if (r == KEEP) {
						return KEEP;
					}
					if (r == AIR) {
						if (++open > SAMPLES - WHOLE) {
							return AIR; // not wholly inside: Skyrim's surface stays
						}
						continue;
					}
					if (sx == 0.5 && sy == 0.5 && sz == 0.5) {
						centreMaterial = r;
						centreDepth = probe.depth;
					}
				}
			}
		}
		if (y <= floorY) {
			return DIG_BEDROCK;
		}
		// Under the land: dirt, stone, deepslate, then bedrock. In an interior, bedrock as far under
		// the nearest floor over the cell.
		double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
		SkyTri land = landAbove(surfaces, cx, cy, cz);
		double landDepth = land != null ? land.heightAt(cx, cz) - cy : Double.NaN;
		double floorDepth = landDepth;
		if (land == null) {
			SkyTri floor = floorAbove(surfaces, cx, cy, cz);
			floorDepth = floor != null ? floor.heightAt(cx, cz) - cy : Double.NaN;
		}
		if (floorDepth >= BEDROCK_DEPTH) {
			return DIG_BEDROCK;
		}
		if (land != null && Double.isInfinite(centreDepth)) {
			centreMaterial = materialOf(land);
			centreDepth = landDepth;
		}
		int material = underground(centreMaterial, centreDepth);
		if (material == DIG_STONE && land != null) {
			material |= (Math.clamp((int) landDepth, 0, 254) + 1) << DEPTH_SHIFT;
		}
		return material;
	}

	/** The Proto.DIG_* material without the depth classify() may add. */
	public static int base(int material) {
		return material & 0xFF;
	}

	/** The nearest stretch of land (Skyrim's terrain) above a point, within {@link #DEEP_REACH}, or null. */
	public static @Nullable SkyTri landAbove(Surfaces surfaces, double x, double y, double z) {
		return nearestAbove(surfaces, x, y, z, DEEP_REACH, true);
	}

	/** The nearest surface facing up (a floor, the top of something) above a point, within reach of bedrock, or null. */
	static @Nullable SkyTri floorAbove(Surfaces surfaces, double x, double y, double z) {
		return nearestAbove(surfaces, x, y, z, BEDROCK_DEPTH + 8.0, false);
	}

	private static @Nullable SkyTri nearestAbove(Surfaces surfaces, double x, double y, double z, double reach, boolean landOnly) {
		List<SkyTri> column = new ArrayList<>();
		surfaces.near(x - 0.01, y, z - 0.01, x + 0.01, y + reach, z + 0.01, column);
		SkyTri best = null;
		double bestHeight = Double.POSITIVE_INFINITY;
		for (SkyTri t : column) {
			if (t.stairHelper || (landOnly ? !t.terrain : t.ny <= 0.05)) {
				continue;
			}
			double h = t.heightAt(x, z);
			if (!Double.isNaN(h) && h >= y && h < bestHeight) {
				bestHeight = h;
				best = t;
			}
		}
		return best;
	}

	/** What's this deep behind a surface of this material: grass has dirt under it, then stone. */
	public static int underground(int surface, double depth) {
		return switch (surface) {
			case DIG_GRASS, DIG_DIRT -> depth < 3.5 ? DIG_DIRT : DIG_STONE;
			case DIG_MUD -> depth < 3.5 ? DIG_MUD : DIG_STONE;
			case DIG_SNOW -> depth < 1.5 ? DIG_SNOW : depth < 3.5 ? DIG_DIRT : DIG_STONE;
			case DIG_SAND -> depth < 3.5 ? DIG_SAND : DIG_STONE;
			case DIG_GRAVEL -> depth < 2.5 ? DIG_GRAVEL : DIG_STONE;
			case DIG_ASH -> depth < 3.5 ? DIG_ASH : DIG_STONE;
			case DIG_ICE -> depth < 2.5 ? DIG_ICE : DIG_STONE;
			case DIG_COBBLE -> depth < 1.5 ? DIG_COBBLE : DIG_STONE;
			case DIG_OAK_LOG, DIG_SPRUCE_LOG, DIG_BIRCH_LOG -> surface;
			case DIG_PLANKS -> depth < 2.5 ? DIG_PLANKS : DIG_STONE;
			case DIG_METAL -> depth < 1.5 ? DIG_METAL : DIG_STONE;
			case DIG_GLASS, DIG_ORGANIC, DIG_CLOTH, DIG_BONE, DIG_WEB -> depth < 1.0 ? surface : DIG_STONE;
			case DIG_BEDROCK -> DIG_BEDROCK;
			default -> DIG_STONE;
		};
	}

	/** Closest point on the triangle to p (Ericson, Real-Time Collision Detection 5.1.5). */
	static void closestPoint(SkyTri t, double px, double py, double pz, double[] out) {
		double abx = t.bx - t.ax, aby = t.by - t.ay, abz = t.bz - t.az;
		double acx = t.cx - t.ax, acy = t.cy - t.ay, acz = t.cz - t.az;
		double apx = px - t.ax, apy = py - t.ay, apz = pz - t.az;
		double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
		if (d1 <= 0 && d2 <= 0) {
			set(out, t.ax, t.ay, t.az);
			return;
		}
		double bpx = px - t.bx, bpy = py - t.by, bpz = pz - t.bz;
		double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
		if (d3 >= 0 && d4 <= d3) {
			set(out, t.bx, t.by, t.bz);
			return;
		}
		double vc = d1 * d4 - d3 * d2;
		if (vc <= 0 && d1 >= 0 && d3 <= 0) {
			double v = d1 / (d1 - d3);
			set(out, t.ax + abx * v, t.ay + aby * v, t.az + abz * v);
			return;
		}
		double cpx = px - t.cx, cpy = py - t.cy, cpz = pz - t.cz;
		double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
		if (d6 >= 0 && d5 <= d6) {
			set(out, t.cx, t.cy, t.cz);
			return;
		}
		double vb = d5 * d2 - d1 * d6;
		if (vb <= 0 && d2 >= 0 && d6 <= 0) {
			double w = d2 / (d2 - d6);
			set(out, t.ax + acx * w, t.ay + acy * w, t.az + acz * w);
			return;
		}
		double va = d3 * d6 - d5 * d4;
		if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
			double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
			set(out, t.bx + (t.cx - t.bx) * w, t.by + (t.cy - t.by) * w, t.bz + (t.cz - t.bz) * w);
			return;
		}
		double denom = 1.0 / (va + vb + vc);
		double v = vb * denom, w = vc * denom;
		set(out, t.ax + abx * v + acx * w, t.ay + aby * v + acy * w, t.az + abz * v + acz * w);
	}

	private static void set(double[] out, double x, double y, double z) {
		out[0] = x;
		out[1] = y;
		out[2] = z;
	}
}
