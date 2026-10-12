package dev.skycraft.worlds;

import java.util.Random;

/**
 * Home's terrain: Steve's old seed, built the old way. Two 16 octave noises are blended by an 8 octave
 * selector and pulled toward a rolling height, sampled every 4 blocks across and 8 up and filled in by
 * trilinear interpolation, then topped with grass over three of dirt (sand on the beaches), water below
 * sea level and bedrock at the floor. The finest octave overflows at {@link FarLands#EDGE}.
 *
 * Around {@link SteveCamp} the ground is levelled to {@link SteveCamp#FLOOR_Y}, with a gravel trail east to
 * the Far Lands and a crack cut through the wall at the end of it.
 *
 * Pure: blocks come out as {@link Block} values that {@link HomeChunkGenerator} maps to Minecraft states.
 */
public final class HomeTerrain {
	/** Steve's seed: the one behind the old title screen panorama. */
	public static final long SEED = 3_257_840_388_504_953_787L;
	public static final int MIN_Y = 0;
	public static final int HEIGHT = 256;
	/** Terrain is generated below this height, like the old 128 block world; above is open sky. */
	public static final int TOP = 128;
	public static final int SEA_LEVEL = 64;
	static final int CELL_XZ = 4;
	static final int CELL_Y = 8;
	static final int CORNERS_Y = TOP / CELL_Y + 1;

	public enum Block { AIR, STONE, DIRT, GRASS, SAND, GRAVEL, WATER, BEDROCK }

	private final ClassicNoise.Octaves low;
	private final ClassicNoise.Octaves high;
	private final ClassicNoise.Octaves selector;
	private final ClassicNoise.Octaves hills;
	private final ClassicNoise.Octaves beach;
	private final long seed;

	public HomeTerrain(long seed) {
		this.seed = seed;
		Random random = new Random(seed);
		low = new ClassicNoise.Octaves(random, 16);
		high = new ClassicNoise.Octaves(random, 16);
		selector = new ClassicNoise.Octaves(random, 8);
		hills = new ClassicNoise.Octaves(random, 8);
		beach = new ClassicNoise.Octaves(random, 4);
	}

	/** Raw density at a block position before the camp, trail and crack shaping; positive is solid. */
	public double density(int x, int y, int z) {
		double nx = x * FarLands.SCALE;
		double ny = y * FarLands.SCALE_Y;
		double nz = z * FarLands.SCALE;
		double a = low.sample(nx, ny, nz);
		double b = high.sample(nx, ny, nz);
		double s = selector.sample(nx / 80.0, ny / 160.0, nz / 80.0) * 3.0 + 0.5;
		double blend = s <= 0 ? a : s >= 1 ? b : a + (b - a) * s;
		double target = 73.0 + hills.sample(x * 0.004, 0.0, z * 0.004) * 44.0;
		return blend * 84.0 + (target - y);
	}

	/** Densities at one column of cell corners, y = 0, 8, ..., TOP. */
	double[] cornerColumn(int x, int z) {
		double[] out = new double[CORNERS_Y];
		for (int i = 0; i < CORNERS_Y; i++) {
			out[i] = density(x, i * CELL_Y, z);
		}
		return out;
	}

	/** Blocks of one chunk, indexed [(y * 16 + z) * 16 + x] for y in [0, TOP). */
	public Block[] chunk(int chunkX, int chunkZ) {
		int x0 = chunkX * 16;
		int z0 = chunkZ * 16;
		double[][][] corners = new double[5][5][];
		for (int cx = 0; cx < 5; cx++) {
			for (int cz = 0; cz < 5; cz++) {
				corners[cx][cz] = cornerColumn(x0 + cx * CELL_XZ, z0 + cz * CELL_XZ);
			}
		}
		Block[] out = new Block[16 * 16 * TOP];
		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				Block[] col = shapeColumn(x0 + lx, z0 + lz, corners, lx, lz);
				for (int y = 0; y < TOP; y++) {
					out[(y * 16 + lz) * 16 + lx] = col[y];
				}
			}
		}
		return out;
	}

	/** Blocks of one column, y in [0, TOP). */
	public Block[] column(int x, int z) {
		int cx0 = Math.floorDiv(x, CELL_XZ) * CELL_XZ;
		int cz0 = Math.floorDiv(z, CELL_XZ) * CELL_XZ;
		double[][][] corners = new double[2][2][];
		for (int i = 0; i < 2; i++) {
			for (int j = 0; j < 2; j++) {
				corners[i][j] = cornerColumn(cx0 + i * CELL_XZ, cz0 + j * CELL_XZ);
			}
		}
		// Reuse the chunk path with a 2 by 2 corner grid: local offsets are relative to the cell.
		return shapeColumn(x, z, corners, x - cx0, z - cz0);
	}

	/** Highest solid (non air, non water) block of the column, or -1. */
	public int surfaceY(int x, int z) {
		Block[] col = column(x, z);
		for (int y = TOP - 1; y >= 0; y--) {
			if (col[y] != Block.AIR && col[y] != Block.WATER) {
				return y;
			}
		}
		return -1;
	}

	/**
	 * Interpolates the corner densities for column (x, z), whose offset inside the corner grid is (lx, lz),
	 * applies the camp shaping, and lays the surface.
	 */
	private Block[] shapeColumn(int x, int z, double[][][] corners, int lx, int lz) {
		int ci = lx / CELL_XZ;
		int cj = lz / CELL_XZ;
		double tx = (lx % CELL_XZ) / (double) CELL_XZ;
		double tz = (lz % CELL_XZ) / (double) CELL_XZ;
		double[] c00 = corners[ci][cj];
		double[] c10 = corners[ci + 1][cj];
		double[] c01 = corners[ci][cj + 1];
		double[] c11 = corners[ci + 1][cj + 1];
		double flat = flatWeight(x, z);
		boolean crack = FarLands.crackColumn(x, z);
		boolean[] solid = new boolean[TOP];
		for (int y = 0; y < TOP; y++) {
			int k = y / CELL_Y;
			double ty = (y % CELL_Y) / (double) CELL_Y;
			double d0 = lerp(tz, lerp(tx, c00[k], c10[k]), lerp(tx, c01[k], c11[k]));
			double d1 = lerp(tz, lerp(tx, c00[k + 1], c10[k + 1]), lerp(tx, c01[k + 1], c11[k + 1]));
			double d = lerp(ty, d0, d1);
			if (flat > 0) {
				d = lerp(flat, d, SteveCamp.FLOOR_Y + 0.5 - y);
			}
			if (crack) {
				d = y > SteveCamp.FLOOR_Y ? -1.0 : y >= SteveCamp.FLOOR_Y - 4 ? 1.0 : d;
			}
			solid[y] = d > 0;
		}
		return surface(x, z, solid);
	}

	/** Grass, dirt, sand, gravel, water and bedrock over the solid/air mask. */
	private Block[] surface(int x, int z, boolean[] solid) {
		Block[] col = new Block[TOP];
		boolean sandy = beach.sample(x * 0.03, 0.0, z * 0.03) > 0.0;
		boolean trail = onTrail(x, z);
		int depth = -1;
		boolean runIsSand = false;
		for (int y = TOP - 1; y >= 0; y--) {
			if (!solid[y]) {
				col[y] = y < SEA_LEVEL ? Block.WATER : Block.AIR;
				depth = -1;
				continue;
			}
			depth++;
			Block b;
			if (depth == 0) {
				boolean underwater = y < SEA_LEVEL - 1;
				runIsSand = sandy && y >= SEA_LEVEL - 4 && y <= SEA_LEVEL + 1;
				if ((trail && y == SteveCamp.FLOOR_Y) || (FarLands.crackColumn(x, z) && y == SteveCamp.FLOOR_Y)) {
					b = Block.GRAVEL;
				} else if (runIsSand) {
					b = Block.SAND;
				} else if (underwater) {
					b = Block.DIRT;
				} else {
					b = Block.GRASS;
				}
			} else if (depth <= 3) {
				b = runIsSand ? Block.SAND : Block.DIRT;
			} else {
				b = Block.STONE;
			}
			col[y] = b;
		}
		col[0] = Block.BEDROCK;
		for (int y = 1; y <= 4; y++) {
			if (bedrockRoll(x, y, z) < 5 - y) {
				col[y] = Block.BEDROCK;
			}
		}
		return col;
	}

	/** 0 to 4, from a hash of the position and seed, for the ragged bedrock floor. */
	private int bedrockRoll(int x, int y, int z) {
		long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (y * 0x165667B19E3779F9L);
		h ^= h >>> 33;
		h *= 0xFF51AFD7ED558CCDL;
		h ^= h >>> 33;
		return (int) Math.floorMod(h, 5L);
	}

	/** The gravel trail: the camp's centre line from just outside the door to the wall. */
	static boolean onTrail(int x, int z) {
		return z == SteveCamp.ORIGIN_Z && x >= SteveCamp.ORIGIN_X + 3 && x < FarLands.EDGE;
	}

	/**
	 * How much the ground is levelled to the camp floor, 0 to 1: fully around the camp and along a 7 wide
	 * corridor to the wall, fading out over {@link #FADE} blocks. Nothing is levelled in the Far Lands.
	 */
	static double flatWeight(int x, int z) {
		if (x >= FarLands.EDGE || x < SteveCamp.ORIGIN_X - 30) {
			return 0.0;
		}
		int campWest = SteveCamp.ORIGIN_X - 8;
		int campEast = SteveCamp.ORIGIN_X + SteveCamp.FIRST_SIGN_DX + SteveCamp.SIGN_COUNT * SteveCamp.SIGN_SPACING + 4;
		int halfWidth = x <= campEast ? 8 : 3;
		double outside = Math.max(0, Math.abs(z - SteveCamp.ORIGIN_Z) - halfWidth);
		if (x < campWest) {
			outside = Math.hypot(outside, campWest - x);
		}
		if (outside >= FADE) {
			return 0.0;
		}
		double t = 1.0 - outside / FADE;
		return t * t * (3 - 2 * t);
	}

	static final double FADE = 8.0;

	static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}
}
