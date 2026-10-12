package dev.skycraft.worlds;

/**
 * Where Steve's old seed breaks. Home's finest terrain octave runs at {@link #SCALE} noise units per block,
 * so its lattice coordinate passes 2^31 between block {@link #EDGE} - 1 and block {@link #EDGE}. From there
 * on {@link ClassicNoise#lattice} saturates, the fraction it leaves behind grows by about 171 a block, and the
 * density turns into stretched, layered walls: the Far Lands, on both signs of both axes.
 *
 * Pure: no Minecraft classes.
 */
public final class FarLands {
	/** First block coordinate, on |x| or |z|, where the terrain is broken. */
	public static final int EDGE = 12_550_821;
	/**
	 * Noise units per block for the finest octave (old terrain used 684.412 per 4-block cell, about 171.1
	 * per block). This value puts 2^31 exactly halfway between EDGE - 1 and EDGE, so the edge sits on the same
	 * block at +x, -x, +z and -z.
	 */
	public static final double SCALE = 2147483648.0 / (EDGE - 0.5);
	/** Vertical noise units per block (684.412 per 8-block cell); never overflows inside the world. */
	public static final double SCALE_Y = 684.412 / 8.0;
	/** The crack Steve walked through: a slot in the wall straight out from his camp. */
	public static final int CRACK_DEPTH = 48;
	public static final int CRACK_HALF_WIDTH = 1;

	private FarLands() {}

	/** True where the terrain is broken: |x| or |z| at or past {@link #EDGE}. */
	public static boolean isFar(long x, long z) {
		return Math.abs(x) >= EDGE || Math.abs(z) >= EDGE;
	}

	/** True inside the crack: just past the +x edge, centred on the camp's z, above the camp's ground. */
	public static boolean inCrack(int x, int y, int z) {
		return x >= EDGE && x < EDGE + CRACK_DEPTH && Math.abs(z - SteveCamp.ORIGIN_Z) <= CRACK_HALF_WIDTH && y > SteveCamp.FLOOR_Y;
	}

	/** True for the crack's floor column (the ground the crack's air sits on). */
	public static boolean crackColumn(int x, int z) {
		return x >= EDGE && x < EDGE + CRACK_DEPTH && Math.abs(z - SteveCamp.ORIGIN_Z) <= CRACK_HALF_WIDTH;
	}
}
