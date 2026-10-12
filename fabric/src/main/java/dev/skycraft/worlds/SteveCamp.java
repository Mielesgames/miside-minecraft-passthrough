package dev.skycraft.worlds;

import java.util.ArrayList;
import java.util.List;

/**
 * Steve's last camp in Home, a little short of the Far Lands: a dirt hut with a bed, a chest of old items and
 * a lectern holding his journal, and a row of signs along his trail counting the days he walked. The trail
 * runs east to the crack in the wall ({@link FarLands#inCrack}).
 *
 * Positions are relative to the camp origin: dx and dz from ({@link #ORIGIN_X}, {@link #ORIGIN_Z}), dy from
 * the first air block above the ground ({@link #FLOOR_Y} + 1). Pure: the Minecraft side
 * ({@link HomeChunkGenerator}) turns pieces into blocks.
 */
public final class SteveCamp {
	/** Centre of the hut. */
	public static final int ORIGIN_X = 12_550_700;
	public static final int ORIGIN_Z = 0;
	/** Top block of the levelled ground under the camp, the trail and the crack floor. */
	public static final int FLOOR_Y = 70;
	/**
	 * Days walked on the first sign. 12,550,700 blocks at walking speed (4.317 blocks a second, 1,200
	 * seconds a day) is about 2,423 days; the last sign, nearest the wall, reads that.
	 */
	public static final int FIRST_DAY = 2417;
	public static final int SIGN_COUNT = 7;
	/** Where the trail and its signs start (dx), and the spacing of the signs. */
	public static final int FIRST_SIGN_DX = 8;
	public static final int SIGN_SPACING = 3;
	public static final int SIGN_DZ = 2;

	public static final String JOURNAL_TITLE = "Journal";
	public static final String JOURNAL_AUTHOR = "Steve";
	/** Placeholder pages; the real journal is still to be written. */
	public static final List<String> JOURNAL_PAGES = List.of(
		"[PLACEHOLDER, page 1]\n\nSteve's journal goes here. Not written yet.",
		"[PLACEHOLDER, page 2]\n\nMore of the walk east.",
		"[PLACEHOLDER, page 3]\n\nThe last entry, written in sight of the wall. It speaks of the stone in the crack: Seamstone."
	);

	/** Beta era things a long walker would still carry. */
	public static final List<Stack> CHEST = List.of(
		new Stack("minecraft:cooked_porkchop", 5),
		new Stack("minecraft:bread", 3),
		new Stack("minecraft:apple", 2),
		new Stack("minecraft:torch", 23),
		new Stack("minecraft:cobblestone", 41),
		new Stack("minecraft:stone_pickaxe", 1),
		new Stack("minecraft:flint_and_steel", 1),
		new Stack("minecraft:compass", 1),
		new Stack("minecraft:clock", 1)
	);

	public record Stack(String item, int count) {}

	public enum Facing { NORTH, SOUTH, EAST, WEST }

	public enum Piece { AIR, DIRT, BED_FOOT, BED_HEAD, CHEST, CRAFTING_TABLE, FURNACE, LECTERN, WALL_TORCH, SIGN }

	/** One block of the camp. sign is the sign's index (0 is the first, westmost) or -1. */
	public record Placement(int dx, int dy, int dz, Piece piece, Facing facing, int sign) {
		Placement(int dx, int dy, int dz, Piece piece) {
			this(dx, dy, dz, piece, Facing.NORTH, -1);
		}

		Placement(int dx, int dy, int dz, Piece piece, Facing facing) {
			this(dx, dy, dz, piece, facing, -1);
		}
	}

	private static final List<Placement> LAYOUT = build();

	private SteveCamp() {}

	public static List<Placement> layout() {
		return LAYOUT;
	}

	private static List<Placement> build() {
		List<Placement> fixtures = new ArrayList<>();
		// Furniture. A bed's facing points from its foot to its head.
		fixtures.add(new Placement(0, 0, -1, Piece.BED_FOOT, Facing.WEST));
		fixtures.add(new Placement(-1, 0, -1, Piece.BED_HEAD, Facing.WEST));
		fixtures.add(new Placement(1, 0, -1, Piece.CHEST, Facing.SOUTH));
		fixtures.add(new Placement(-1, 0, 1, Piece.CRAFTING_TABLE));
		fixtures.add(new Placement(0, 0, 1, Piece.FURNACE, Facing.NORTH));
		fixtures.add(new Placement(1, 0, 1, Piece.LECTERN, Facing.NORTH));
		// Wall torches point away from the wall they hang on.
		fixtures.add(new Placement(-1, 1, 0, Piece.WALL_TORCH, Facing.EAST));
		fixtures.add(new Placement(3, 1, 1, Piece.WALL_TORCH, Facing.EAST));
		// The signs, along the south side of the trail, reading toward it (north).
		for (int i = 0; i < SIGN_COUNT; i++) {
			fixtures.add(new Placement(FIRST_SIGN_DX + i * SIGN_SPACING, 0, SIGN_DZ, Piece.SIGN, Facing.NORTH, i));
		}
		List<Placement> out = new ArrayList<>();
		// A 5 by 5 dirt hut, walls three high under a dirt roof, doorway facing east toward the wall.
		// The shell goes first: walls and roof, then air inside and in the doorway around the fixtures.
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				for (int dy = 0; dy <= 2; dy++) {
					boolean door = dx == 2 && dz == 0 && dy <= 1;
					if (wall && !door) {
						out.add(new Placement(dx, dy, dz, Piece.DIRT));
					} else if (!occupied(fixtures, dx, dy, dz)) {
						out.add(new Placement(dx, dy, dz, Piece.AIR));
					}
				}
				out.add(new Placement(dx, 3, dz, Piece.DIRT));
			}
		}
		out.addAll(fixtures);
		return List.copyOf(out);
	}

	private static boolean occupied(List<Placement> fixtures, int dx, int dy, int dz) {
		for (Placement p : fixtures) {
			if (p.dx() == dx && p.dy() == dy && p.dz() == dz) {
				return true;
			}
		}
		return false;
	}

	/** The four lines of sign i: the day count on the second line. */
	public static List<String> signLines(int sign) {
		return List.of("", "Day " + (FIRST_DAY + sign), "", "");
	}

	public static int minDx() {
		return LAYOUT.stream().mapToInt(Placement::dx).min().orElseThrow();
	}

	public static int maxDx() {
		return LAYOUT.stream().mapToInt(Placement::dx).max().orElseThrow();
	}

	public static int minDz() {
		return LAYOUT.stream().mapToInt(Placement::dz).min().orElseThrow();
	}

	public static int maxDz() {
		return LAYOUT.stream().mapToInt(Placement::dz).max().orElseThrow();
	}

	/**
	 * The chunk whose decoration places the camp. The camp must fit in this chunk and its eight neighbours,
	 * which is all a chunk's decoration step may write to.
	 */
	public static int anchorChunkX() {
		return Math.floorDiv(ORIGIN_X + (minDx() + maxDx()) / 2, 16);
	}

	public static int anchorChunkZ() {
		return Math.floorDiv(ORIGIN_Z + (minDz() + maxDz()) / 2, 16);
	}

	/** Arrival in front of the hut on the trail, standing on the ground, looking east at the wall. */
	public static final double ARRIVAL_X = ORIGIN_X + 5.5;
	public static final double ARRIVAL_Y = FLOOR_Y + 1.0;
	public static final double ARRIVAL_Z = ORIGIN_Z + 0.5;
	/** Minecraft yaw for facing +x (east). */
	public static final float ARRIVAL_YAW = -90.0f;
}
