package dev.skycraft.breach;

/**
 * One breach antechamber's shape and place (pure, no Minecraft classes). Rooms are laid out in local
 * coordinates: {@code u} across (to the right when facing the sealed door), {@code v} up from the
 * floor and {@code w} toward the sealed door. The interior is {@link #WIDTH} x {@link #HEIGHT} x
 * {@link #LENGTH}; a shell {@link #shell()} blocks thick closes it all round, and the wall at
 * {@code w = LENGTH} holds the sealed door (two cells wide, two high) in a frame.
 *
 * @param world   the Skyrim worldspace the dungeon is in
 * @param doorRef the dungeon's exterior load door (sent back in EV_ENTER_DUNGEON)
 * @param bx      world x of local (u 0, w 0)
 * @param floorY  world y of the interior floor (local v 0)
 * @param bz      world z of local (u 0, w 0)
 * @param facing  the direction toward the sealed door, as a 2D direction value (0 south, 1 west,
 *                2 north, 3 east, as Minecraft's Direction.get2DDataValue)
 * @param shell   shell thickness (1 or 2)
 * @param theme   Proto.THEME_*
 */
public record BreachRoom(int world, int doorRef, int bx, int floorY, int bz, int facing, int shell, int theme) {
	public static final int WIDTH = 8;
	public static final int HEIGHT = 4;
	public static final int LENGTH = 7;
	/** The sealed door's left cell across the room (the right one is next to it). */
	public static final int DOOR_U = 3;

	/** What a cell of the room is. */
	public static final int OUTSIDE = 0;
	public static final int INTERIOR = 1;
	public static final int SHELL = 2;
	public static final int FRAME = 3;
	public static final int DOOR = 4;

	// Furniture slots, local {u, v, w}, all inside the interior.
	public static final int[] CHEST_SLOT = { 0, 0, 3 };
	public static final int[] LANTERN_SLOT = { DOOR_U, HEIGHT - 1, 3 };
	/** Wall lights either side of the sealed door, on its frame. */
	public static final int[][] TORCH_SLOTS = { { DOOR_U - 1, 2, LENGTH - 1 }, { DOOR_U + 2, 2, LENGTH - 1 } };
	public static final int[][] FLOOR_CORNERS = { { 0, 0, 0 }, { WIDTH - 1, 0, 0 }, { 0, 0, LENGTH - 1 }, { WIDTH - 1, 0, LENGTH - 1 } };
	public static final int[][] CEILING_CORNERS = { { 0, HEIGHT - 1, 0 }, { WIDTH - 1, HEIGHT - 1, 0 }, { 0, HEIGHT - 1, LENGTH - 1 }, { WIDTH - 1, HEIGHT - 1, LENGTH - 1 } };

	private static final int[] STEP_X = { 0, -1, 0, 1 }; // south, west, north, east
	private static final int[] STEP_Z = { 1, 0, -1, 0 };

	public static int stepX(int facing) {
		return STEP_X[Math.floorMod(facing, 4)];
	}

	public static int stepZ(int facing) {
		return STEP_Z[Math.floorMod(facing, 4)];
	}

	/** The facing a quarter turn clockwise seen from above (Direction.getClockWise). */
	public static int clockwise(int facing) {
		return Math.floorMod(facing + 1, 4);
	}

	public int fx() {
		return stepX(this.facing);
	}

	public int fz() {
		return stepZ(this.facing);
	}

	/** The facing of local +u (to the right when facing the sealed door). */
	public int right() {
		return clockwise(this.facing);
	}

	public int rx() {
		return stepX(right());
	}

	public int rz() {
		return stepZ(right());
	}

	public BreachRoom withFloorY(int y) {
		return new BreachRoom(this.world, this.doorRef, this.bx, y, this.bz, this.facing, this.shell, this.theme);
	}

	// ---- local <-> world ----------------------------------------------------------------------

	public int u(int x, int z) {
		return (x - this.bx) * rx() + (z - this.bz) * rz();
	}

	public int v(int y) {
		return y - this.floorY;
	}

	public int w(int x, int z) {
		return (x - this.bx) * fx() + (z - this.bz) * fz();
	}

	public int x(int u, int w) {
		return this.bx + u * rx() + w * fx();
	}

	public int y(int v) {
		return this.floorY + v;
	}

	public int z(int u, int w) {
		return this.bz + u * rz() + w * fz();
	}

	/** What the world cell (x, y, z) is in this room. */
	public int kind(int x, int y, int z) {
		return kindLocal(u(x, z), v(y), w(x, z), this.shell);
	}

	/** What the local cell (u, v, w) is in a room with this shell thickness. */
	public static int kindLocal(int u, int v, int w, int shell) {
		if (u < -shell || u >= WIDTH + shell || v < -shell || v >= HEIGHT + shell || w < -shell || w >= LENGTH + shell) {
			return OUTSIDE;
		}
		if (u >= 0 && u < WIDTH && v >= 0 && v < HEIGHT && w >= 0 && w < LENGTH) {
			return INTERIOR;
		}
		if (w == LENGTH) {
			boolean doorColumn = u == DOOR_U || u == DOOR_U + 1;
			if (doorColumn && (v == 0 || v == 1)) {
				return DOOR;
			}
			if ((doorColumn && v == 2) || ((u == DOOR_U - 1 || u == DOOR_U + 2) && v >= 0 && v <= 2)) {
				return FRAME;
			}
		}
		return SHELL;
	}

	// ---- bounds -------------------------------------------------------------------------------

	public int minX() {
		return Math.min(x(-this.shell, -this.shell), x(WIDTH - 1 + this.shell, LENGTH - 1 + this.shell));
	}

	public int maxX() {
		return Math.max(x(-this.shell, -this.shell), x(WIDTH - 1 + this.shell, LENGTH - 1 + this.shell));
	}

	public int minY() {
		return this.floorY - this.shell;
	}

	public int maxY() {
		return this.floorY + HEIGHT - 1 + this.shell;
	}

	public int minZ() {
		return Math.min(z(-this.shell, -this.shell), z(WIDTH - 1 + this.shell, LENGTH - 1 + this.shell));
	}

	public int maxZ() {
		return Math.max(z(-this.shell, -this.shell), z(WIDTH - 1 + this.shell, LENGTH - 1 + this.shell));
	}

	/** Whether the cell is in the room's outer box (shell included). */
	public boolean contains(int x, int y, int z) {
		return x >= minX() && x <= maxX() && y >= minY() && y <= maxY() && z >= minZ() && z <= maxZ();
	}

	/** Whether a point (an entity's feet) is inside the open interior. */
	public boolean inInterior(double x, double y, double z) {
		int cx = (int) Math.floor(x), cy = (int) Math.floor(y), cz = (int) Math.floor(z);
		return kind(cx, cy, cz) == INTERIOR;
	}

	/** Distance from a point to the room's outer box (0 inside it). */
	public double distanceTo(double x, double y, double z) {
		double dx = Math.max(0.0, Math.max(minX() - x, x - (maxX() + 1)));
		double dy = Math.max(0.0, Math.max(minY() - y, y - (maxY() + 1)));
		double dz = Math.max(0.0, Math.max(minZ() - z, z - (maxZ() + 1)));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** The point of the room's outer box nearest to (x, y, z), into {@code out}. */
	public void nearestPoint(double x, double y, double z, double[] out) {
		out[0] = Math.clamp(x, minX(), maxX() + 1.0);
		out[1] = Math.clamp(y, minY(), maxY() + 1.0);
		out[2] = Math.clamp(z, minZ(), maxZ() + 1.0);
	}

	public boolean overlaps(BreachRoom other, int margin) {
		return minX() - margin <= other.maxX() && maxX() + margin >= other.minX() && minY() - margin <= other.maxY() && maxY() + margin >= other.minY()
			&& minZ() - margin <= other.maxZ() && maxZ() + margin >= other.minZ();
	}

	/** Which of the theme's shell blocks this cell is (BreachPlanner.MAIN ... ACCENT); stable for the room. */
	public int variant(int x, int y, int z) {
		return BreachPlanner.variant(this.theme, kind(x, y, z), v(y), BreachPlanner.roll(this.doorRef, x, y, z));
	}

	public long key() {
		return BreachPlanner.key(this.world, this.doorRef);
	}
}
