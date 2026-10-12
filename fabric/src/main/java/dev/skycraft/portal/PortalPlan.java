package dev.skycraft.portal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a portal built for the network goes (pure): a 2 by 3 portal in an obsidian frame standing just
 * behind an arrival spot, so a traveller steps out onto the spot facing the way the arrival faces. The
 * Minecraft side (PortalBuilder) clears the air in front, gives it a floor, seals any lava or water round
 * it, builds the frame and lights it.
 *
 * <p>{@code x, y, z} is the lowest portal block toward negative X or Z, as in PortalStore.Portal; {@code side}
 * is the exit side. Positions are {x, y, z} block triples.
 */
public record PortalPlan(int x, int y, int z, int axis, int side) {
	public static final int WIDTH = 2;
	public static final int HEIGHT = 3;
	/** Rows of open floor in front of the portal (the exit row and one more). */
	public static final int APRON = 2;

	/**
	 * The portal behind feet block {@code bx, by, bz} for a traveller facing {@code yaw} (MC degrees: 0 south,
	 * 90 west, 180 north, -90 east), so its exit is that spot facing that way.
	 */
	public static PortalPlan behind(int bx, int by, int bz, float yaw) {
		float y = ((yaw % 360.0f) + 360.0f) % 360.0f;
		if (y >= 315.0f || y < 45.0f) { // facing south (+z): the portal is north of the spot
			return new PortalPlan(bx - 1, by, bz - 1, PortalStore.AXIS_X, 1);
		}
		if (y < 135.0f) { // facing west (-x): the portal is east
			return new PortalPlan(bx + 1, by, bz - 1, PortalStore.AXIS_Z, -1);
		}
		if (y < 225.0f) { // facing north (-z): the portal is south
			return new PortalPlan(bx - 1, by, bz + 1, PortalStore.AXIS_X, -1);
		}
		return new PortalPlan(bx - 1, by, bz - 1, PortalStore.AXIS_Z, 1); // facing east (+x): the portal is west
	}

	/** The same plan moved {@code along} blocks along its axis (for a second portal beside the first). */
	public PortalPlan shifted(int along) {
		return this.axis == PortalStore.AXIS_X ? new PortalPlan(this.x + along, this.y, this.z, this.axis, this.side)
			: new PortalPlan(this.x, this.y, this.z + along, this.axis, this.side);
	}

	/**
	 * Places for further portals onto the same arrival, nearest first: side by side along the axis, a block
	 * of ground between frames.
	 */
	public List<PortalPlan> sites(int count) {
		List<PortalPlan> out = new ArrayList<>(count);
		out.add(this);
		for (int i = 1; out.size() < count; i++) {
			out.add(this.shifted(5 * i));
			if (out.size() < count) {
				out.add(this.shifted(-5 * i));
			}
		}
		return out;
	}

	/** Where travellers arrive, as PortalStore.Portal.exit gives it: feet {x, y, z} and yaw. */
	public double[] exit() {
		return new PortalStore.Portal(0, 0, 0, this.x, this.y, this.z, this.axis, WIDTH, HEIGHT, this.side, 0, "").exit();
	}

	/** A block {@code a} along the axis, {@code o} toward the exit side and {@code dy} up from the lowest portal block. */
	public int[] at(int a, int o, int dy) {
		if (this.axis == PortalStore.AXIS_X) {
			return new int[] { this.x + a, this.y + dy, this.z + o * this.side };
		}
		return new int[] { this.x + o * this.side, this.y + dy, this.z + a };
	}

	/** The portal blocks. */
	public List<int[]> portal() {
		List<int[]> out = new ArrayList<>();
		for (int dy = 0; dy < HEIGHT; dy++) {
			for (int a = 0; a < WIDTH; a++) {
				out.add(this.at(a, 0, dy));
			}
		}
		return out;
	}

	/** The obsidian frame, corners included: 14 blocks round the portal in its plane. */
	public List<int[]> frame() {
		List<int[]> out = new ArrayList<>();
		for (int dy = -1; dy <= HEIGHT; dy++) {
			for (int a = -1; a <= WIDTH; a++) {
				if (dy == -1 || dy == HEIGHT || a == -1 || a == WIDTH) {
					out.add(this.at(a, 0, dy));
				}
			}
		}
		return out;
	}

	/** Air in front: the apron rows, as wide as the frame, as high as the portal. */
	public List<int[]> clear() {
		List<int[]> out = new ArrayList<>();
		for (int o = 1; o <= APRON; o++) {
			for (int dy = 0; dy < HEIGHT; dy++) {
				for (int a = -1; a <= WIDTH; a++) {
					out.add(this.at(a, o, dy));
				}
			}
		}
		return out;
	}

	/** The floor under the apron. */
	public List<int[]> floor() {
		List<int[]> out = new ArrayList<>();
		for (int o = 1; o <= APRON; o++) {
			for (int a = -1; a <= WIDTH; a++) {
				out.add(this.at(a, o, -1));
			}
		}
		return out;
	}

	/**
	 * Every other block within one of the work (behind the frame, above the apron, round the sides, under the
	 * floor): lava, water or fire there is sealed off.
	 */
	public List<int[]> shell() {
		Set<Long> taken = new HashSet<>();
		for (List<int[]> part : List.of(this.portal(), this.frame(), this.clear(), this.floor())) {
			for (int[] p : part) {
				taken.add(key(p));
			}
		}
		List<int[]> out = new ArrayList<>();
		for (int o = -1; o <= APRON + 1; o++) {
			for (int dy = -2; dy <= HEIGHT + 1; dy++) {
				for (int a = -2; a <= WIDTH + 1; a++) {
					int[] p = this.at(a, o, dy);
					if (!taken.contains(key(p))) {
						out.add(p);
					}
				}
			}
		}
		return out;
	}

	/** The lowest and highest corners of everything the build touches: {minX, minY, minZ, maxX, maxY, maxZ}. */
	public int[] bounds() {
		int[] b = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
		for (int o : new int[] { -1, APRON + 1 }) {
			for (int dy : new int[] { -2, HEIGHT + 1 }) {
				for (int a : new int[] { -2, WIDTH + 1 }) {
					int[] p = this.at(a, o, dy);
					for (int i = 0; i < 3; i++) {
						b[i] = Math.min(b[i], p[i]);
						b[i + 3] = Math.max(b[i + 3], p[i]);
					}
				}
			}
		}
		return b;
	}

	/** Whether no portal of the network in this dimension (frames and a block round them) is in the way. */
	public boolean freeIn(PortalStore store, String dimension) {
		int[] b = this.bounds();
		return store.within(dimension, b[0], b[1], b[2], b[3], b[4], b[5], 1).isEmpty();
	}

	private static long key(int[] p) {
		return ((long) p[0] & 0x3FFFFFF) << 38 | ((long) p[2] & 0x3FFFFFF) << 12 | ((long) p[1] & 0xFFF);
	}
}
