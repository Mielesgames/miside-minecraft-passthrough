package dev.skycraft.breach;

import static dev.skycraft.link.Proto.THEME_CAVE;
import static dev.skycraft.link.Proto.THEME_DWEMER;
import static dev.skycraft.link.Proto.THEME_FALMER;
import static dev.skycraft.link.Proto.THEME_FORT;
import static dev.skycraft.link.Proto.THEME_LAIR;
import static dev.skycraft.link.Proto.THEME_MINE;
import static dev.skycraft.link.Proto.THEME_NORDIC;

/**
 * Where a breach antechamber goes and how hard it is to get into, from the dungeon entrance alone
 * (pure, no Minecraft classes, so it's the same every session):
 * <ul>
 * <li>Across: the sealed door is right under the entrance door, and the room runs out from it
 * along the entrance door's facing.</li>
 * <li>Down: the top of the room is {@link #depthFor} blocks under the lowest the land gets over
 * it: 12 for low level dungeons, deeper for higher ones, never less than {@link #MIN_DEPTH} and
 * never down at bedrock ({@link #BEDROCK_DEPTH} under the land).</li>
 * <li>Walls: 1 block thick, 2 from level 20; which of the theme's blocks each one is comes from a
 * hash of the door and the cell.</li>
 * <li>Breaking the walls takes a pickaxe of the theme's {@link #tier}.</li>
 * </ul>
 */
public final class BreachPlanner {
	/** Shallowest top of a room under the land. */
	public static final int MIN_DEPTH = 8;
	/** Top of a low level dungeon's room under the land. */
	public static final int SHALLOW_DEPTH = 12;
	/** Top of an unknown level dungeon's room under the land. */
	public static final int UNKNOWN_DEPTH = 16;
	public static final int MAX_DEPTH = 48;
	/** As SkyDig.BEDROCK_DEPTH: cells this far under the land are bedrock. */
	public static final int BEDROCK_DEPTH = 64;
	/** Blocks kept between the room's bottom and bedrock. */
	public static final int BEDROCK_MARGIN = 2;
	/** Dungeons from this level get 2 block thick walls. */
	public static final int THICK_LEVEL = 20;
	/** floorY's answer when the room can't fit (the land too steep there). */
	public static final int NO_ROOM = Integer.MIN_VALUE;

	/** Shell block variants (see BreachBlocks for each theme's blocks). */
	public static final int MAIN = 0;
	public static final int ALT = 1;
	public static final int RARE = 2;
	public static final int ACCENT = 3;

	/** Pickaxe tiers: what the theme's walls need at least. */
	public static final int TIER_WOOD = 0;
	public static final int TIER_STONE = 1;
	public static final int TIER_IRON = 2;
	public static final int TIER_DIAMOND = 3;

	private BreachPlanner() {
	}

	public static long key(int world, int doorRef) {
		return ((long) world << 32) | (doorRef & 0xFFFFFFFFL);
	}

	/** A door's facing from its yaw (MC degrees), as a 2D direction value (0 south, 1 west, 2 north, 3 east). */
	public static int facingFromYaw(float yaw) {
		if (!Float.isFinite(yaw)) {
			return 0;
		}
		return Math.floorMod((int) Math.floor(yaw / 90.0 + 0.5), 4);
	}

	/** How far under the land the top of the room is for a dungeon of this encounter level (0 unknown). */
	public static int depthFor(int level) {
		if (level <= 0) {
			return UNKNOWN_DEPTH;
		}
		return Math.min(MAX_DEPTH, SHALLOW_DEPTH + Math.max(0, level - 5) * 3 / 4);
	}

	/** Shell thickness for a dungeon of this encounter level. */
	public static int shellFor(int level) {
		return level >= THICK_LEVEL ? 2 : 1;
	}

	/**
	 * The room for an entrance door at block (doorX, doorZ): the sealed door's left cell is right
	 * under it, and the room runs out along the door's facing (so the sealed door faces the way into
	 * the dungeon). Its height is left at {@code floorY} until the land over it is known.
	 */
	public static BreachRoom place(int world, int doorRef, int doorX, int doorZ, float yaw, int theme, int level, int floorY) {
		int facing = Math.floorMod(facingFromYaw(yaw) + 2, 4); // toward the sealed door: back toward the entrance
		int fx = BreachRoom.stepX(facing), fz = BreachRoom.stepZ(facing);
		int right = BreachRoom.clockwise(facing);
		int rx = BreachRoom.stepX(right), rz = BreachRoom.stepZ(right);
		int bx = doorX - BreachRoom.DOOR_U * rx - BreachRoom.LENGTH * fx;
		int bz = doorZ - BreachRoom.DOOR_U * rz - BreachRoom.LENGTH * fz;
		return new BreachRoom(world, doorRef, bx, floorY, bz, facing, shellFor(level), normalTheme(theme));
	}

	/** Unknown themes build as caves. */
	public static int normalTheme(int theme) {
		return theme >= THEME_CAVE && theme <= THEME_LAIR ? theme : THEME_CAVE;
	}

	/**
	 * Where to look at the land over a room: a 3 x 3 grid of cells over its outer footprint, as
	 * {x, z} block coordinates (look at the middle of each).
	 */
	public static int[][] footprint(BreachRoom room) {
		int s = room.shell();
		int[] us = { -s, (BreachRoom.WIDTH - 1) / 2, BreachRoom.WIDTH - 1 + s };
		int[] ws = { -s, (BreachRoom.LENGTH - 1) / 2, BreachRoom.LENGTH - 1 + s };
		int[][] out = new int[9][];
		int i = 0;
		for (int u : us) {
			for (int w : ws) {
				out[i++] = new int[] { room.x(u, w), room.z(u, w) };
			}
		}
		return out;
	}

	/**
	 * The interior floor's y for a room under land that goes from {@code minSurface} to
	 * {@code maxSurface} over its footprint, or {@link #NO_ROOM} if it can't be both deep enough
	 * and clear of bedrock there.
	 */
	public static int floorY(int level, int shell, double minSurface, double maxSurface) {
		if (!Double.isFinite(minSurface) || !Double.isFinite(maxSurface) || maxSurface < minSurface) {
			return NO_ROOM;
		}
		int outerHeight = BreachRoom.HEIGHT + 2 * shell;
		int land = (int) Math.floor(minSurface);
		int top = land - depthFor(level); // the highest shell layer
		int bottom = top - outerHeight + 1;
		// Bedrock is BEDROCK_DEPTH under the land over each cell; the highest land counts.
		int lowest = (int) Math.ceil(maxSurface) - BEDROCK_DEPTH + BEDROCK_MARGIN;
		if (bottom < lowest) {
			bottom = lowest;
			top = bottom + outerHeight - 1;
		}
		if (land - top < MIN_DEPTH) {
			return NO_ROOM;
		}
		return bottom + shell;
	}

	/** The pickaxe a theme's walls need at least. */
	public static int tier(int theme) {
		return switch (theme) {
			case THEME_DWEMER, THEME_LAIR -> TIER_IRON;
			case THEME_NORDIC, THEME_FORT, THEME_FALMER -> TIER_STONE;
			default -> TIER_WOOD;
		};
	}

	/** "a stone pickaxe" and so on. */
	public static String pickaxeName(int tier) {
		return switch (tier) {
			case TIER_STONE -> "a stone pickaxe";
			case TIER_IRON -> "an iron pickaxe";
			case TIER_DIAMOND -> "a diamond pickaxe";
			default -> "a pickaxe";
		};
	}

	public static String themeName(int theme) {
		return switch (theme) {
			case THEME_NORDIC -> "Nordic";
			case THEME_DWEMER -> "Dwemer";
			case THEME_FALMER -> "Falmer";
			case THEME_MINE -> "mine";
			case THEME_FORT -> "fort";
			case THEME_LAIR -> "lair";
			default -> "cave";
		};
	}

	/**
	 * Which of the theme's shell blocks a cell is: the frame round the sealed door is the accent,
	 * a mine's ceiling is plank beams, the rest mostly the main block with some others mixed in.
	 * {@code roll} is 0..99 ({@link #roll}).
	 */
	public static int variant(int theme, int kind, int v, int roll) {
		if (kind == BreachRoom.FRAME) {
			return ACCENT;
		}
		if (theme == THEME_MINE) {
			return v >= BreachRoom.HEIGHT ? ALT : roll < 88 ? MAIN : RARE;
		}
		return roll < 65 ? MAIN : roll < 90 ? ALT : RARE;
	}

	/** 0..99 from the door and the cell: the same every time. */
	public static int roll(int doorRef, int x, int y, int z) {
		long h = mix(((long) doorRef << 32) ^ (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L));
		return (int) Math.floorMod(h, 100L);
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
