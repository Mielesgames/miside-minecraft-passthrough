package dev.skycraft.portal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The portal network of one Minecraft world (pure; SkyPortals keeps it in the world's save folder as
 * {@code skycraft-portals.txt}). A portal is a lit Nether portal's rectangle of portal blocks plus where
 * it is: in the Skyrim mirror a Skyrim world id and a Minecraft position (in that world's space, SkySpace;
 * events to Skyrim convert it to the world's own terms), anywhere else a Minecraft dimension
 * ({@link Portal#dimension}).
 *
 * <p>Portals are cracks between places that resemble each other: each is bound to exactly one other
 * portal, for good ({@link #bind}), and leads only there. The one exception is the Time-Wound's crack,
 * which opens one way onto Home's arrival ({@link #openOneWay}). Seamstone breaks the lock: it opens any
 * portal onto any known place ({@link Target}), so every portal, every arrival point and every world the
 * player has been to stays listed. One line per portal:
 * {@code <id> <world> <flags> <x> <y> <z> <axis X|Z> <width> <height> <side> <partner> <place>}, where
 * {@code world} is a Skyrim world (hex FormID) or a dimension id ({@code minecraft:the_nether}); plus
 * {@code next <id>} (ids are never reused, so an attuned gem can't find a newer portal by mistake),
 * {@code oneway <id> <dimension>}, {@code arrival <dimension> <x> <y> <z> <yaw>},
 * {@code visited <dimension>} and {@code ending <player uuid>}.
 */
public final class PortalStore {
	/** Portal blocks run along X (the frame faces north and south). */
	public static final int AXIS_X = 0;
	/** Portal blocks run along Z (the frame faces east and west). */
	public static final int AXIS_Z = 1;
	/** Portal flags: opened in a Skyrim interior cell ({@code world} is that cell's FormID). */
	public static final int INTERIOR = 1;
	/** The largest portal Minecraft lights (PortalShape.MAX_WIDTH and MAX_HEIGHT). */
	public static final int MAX_SIZE = 21;
	/** {@link Portal#dimension} of a portal in the Skyrim mirror (its {@code world} names the Skyrim world). */
	public static final String MIRROR = "";

	public static final String HEADER = "# SkyCraft portal network: id world flags x y z axis width height side partner place";
	private static final String HEADER_2 = "# world: a Skyrim world (hex FormID) or a dimension id. Also: next <id>, oneway <id> <dimension>, "
		+ "arrival <dimension> <x> <y> <z> <yaw>, visited <dimension>, ending <player uuid>";

	/** The network's items (PortalItems registers them as skycraft:<id>). */
	public static final String FILLED_GEM_ID = "filled_soul_gem";
	public static final String ATTUNED_GEM_ID = "attuned_soul_gem";
	/** The Dwemer's key to Tamriel Wilds. */
	public static final String TONAL_KEY_ID = "tonal_key";
	/** The ending's stone: opens any portal onto any known place. */
	public static final String SEAMSTONE_ID = "seamstone";
	public static final List<String> ITEM_IDS = List.of(FILLED_GEM_ID, ATTUNED_GEM_ID, TONAL_KEY_ID, SEAMSTONE_ID);

	/**
	 * One portal. {@code x, y, z} is its lowest portal block toward negative X or Z, {@code width} runs
	 * along its axis, {@code height} up. {@code side} (+1 or -1, along the axis across the portal) is the
	 * side travellers step out on: the side the player stood on when they attuned or bound it.
	 * {@code partner} is the bound portal's id, 0 when unbound. {@code place} is a name for where it is
	 * (in the mirror Skyrim's SkyState town, maybe empty). {@code dimension} is {@link #MIRROR} for a portal
	 * in the Skyrim mirror ({@code world} is then the Skyrim world), else the Minecraft dimension's id
	 * ({@code world} 0).
	 */
	public record Portal(int id, int world, int flags, int x, int y, int z, int axis, int width, int height, int side, int partner, String place,
		String dimension) {
		/** A portal in the Skyrim mirror. */
		public Portal(int id, int world, int flags, int x, int y, int z, int axis, int width, int height, int side, int partner, String place) {
			this(id, world, flags, x, y, z, axis, width, height, side, partner, place, MIRROR);
		}

		public boolean interior() {
			return (this.flags & INTERIOR) != 0;
		}

		/** In the Skyrim mirror (its {@code world} is a Skyrim world), not another dimension. */
		public boolean inMirror() {
			return this.dimension.isEmpty();
		}

		public boolean bound() {
			return this.partner != 0;
		}

		public int maxX() {
			return this.axis == AXIS_X ? this.x + this.width - 1 : this.x;
		}

		public int maxZ() {
			return this.axis == AXIS_Z ? this.z + this.width - 1 : this.z;
		}

		public int maxY() {
			return this.y + this.height - 1;
		}

		/** Whether the block is one of its portal blocks. */
		public boolean contains(int bx, int by, int bz) {
			return bx >= this.x && bx <= this.maxX() && by >= this.y && by <= this.maxY() && bz >= this.z && bz <= this.maxZ();
		}

		/** Whether the block is one of its portal blocks or its frame (one block round them, in their plane). */
		public boolean frameContains(int bx, int by, int bz) {
			if (by < this.y - 1 || by > this.maxY() + 1) {
				return false;
			}
			if (this.axis == AXIS_X) {
				return bz == this.z && bx >= this.x - 1 && bx <= this.maxX() + 1;
			}
			return bx == this.x && bz >= this.z - 1 && bz <= this.maxZ() + 1;
		}

		/** Whether it is exactly this rectangle of portal blocks. */
		public boolean sameShape(int sx, int sy, int sz, int sAxis, int sWidth, int sHeight) {
			return this.x == sx && this.y == sy && this.z == sz && this.axis == sAxis && this.width == sWidth && this.height == sHeight;
		}

		public Portal withPartner(int p) {
			return new Portal(this.id, this.world, this.flags, this.x, this.y, this.z, this.axis, this.width, this.height, this.side, p, this.place,
				this.dimension);
		}

		public Portal withSide(int s) {
			return new Portal(this.id, this.world, this.flags, this.x, this.y, this.z, this.axis, this.width, this.height, s < 0 ? -1 : 1, this.partner,
				this.place, this.dimension);
		}

		/**
		 * Where a traveller arrives: feet {x, y, z} in the middle of the block just in front of the portal
		 * on its {@code side}, at the height of its lowest portal blocks, and the yaw facing away from it
		 * (MC degrees: 0 south, 90 west, 180 north, -90 east).
		 */
		public double[] exit() {
			if (this.axis == AXIS_X) {
				return new double[] { this.x + this.width / 2.0, this.y, this.z + 0.5 + this.side, this.side > 0 ? 0.0 : 180.0 };
			}
			return new double[] { this.x + 0.5 + this.side, this.y, this.z + this.width / 2.0, this.side > 0 ? -90.0 : 90.0 };
		}

		/** The middle of its portal blocks. */
		public double[] center() {
			return new double[] { (this.x + this.maxX() + 1) / 2.0, (this.y + this.maxY() + 1) / 2.0, (this.z + this.maxZ() + 1) / 2.0 };
		}
	}

	/** Where a portal can lead: another portal, or a dimension's arrival point. */
	public sealed interface Target {
		/** The portal with this id (in the mirror or another dimension). */
		record ToPortal(int id) implements Target {
		}

		/** The arrival point of this dimension ({@link #arrival}, else Worlds' default). */
		record ToArrival(String dimension) implements Target {
		}
	}

	/** A recorded arrival point: feet position and yaw. */
	public record Arrival(double x, double y, double z, float yaw) {
	}

	/** What {@link #bind} did. */
	public enum Bind {
		/** The two are bound to each other now. */
		BOUND,
		/** Both ids are the same portal. */
		SAME,
		/** One of them isn't in the network. */
		MISSING,
		/** One of them is bound already (bonds are for good). */
		ALREADY_BOUND
	}

	private final Map<Integer, Portal> portals = new LinkedHashMap<>();
	private final Map<Integer, String> oneWay = new LinkedHashMap<>();
	private final Map<String, Arrival> arrivals = new LinkedHashMap<>();
	private final Set<String> visited = new LinkedHashSet<>();
	private final Set<UUID> endings = new LinkedHashSet<>();
	private int next = 1;

	/** The side of a portal on this axis that a point is on (+1 or -1), for {@link Portal#side}. */
	public static int sideOf(int axis, int x, int z, double px, double pz) {
		return axis == AXIS_X ? (pz >= z + 0.5 ? 1 : -1) : (px >= x + 0.5 ? 1 : -1);
	}

	public @Nullable Portal get(int id) {
		return this.portals.get(id);
	}

	public Collection<Portal> all() {
		return this.portals.values();
	}

	public int size() {
		return this.portals.size();
	}

	/** The id the next new portal gets. */
	public int nextId() {
		return this.next;
	}

	/** The mirror portal of this Skyrim world one of whose portal blocks this is, or null. */
	public @Nullable Portal at(int world, int bx, int by, int bz) {
		for (Portal p : this.portals.values()) {
			if (p.inMirror() && p.world() == world && p.contains(bx, by, bz)) {
				return p;
			}
		}
		return null;
	}

	/** The portal of this other dimension one of whose portal blocks this is, or null. */
	public @Nullable Portal in(String dimension, int bx, int by, int bz) {
		for (Portal p : this.portals.values()) {
			if (!p.inMirror() && p.dimension().equals(dimension) && p.contains(bx, by, bz)) {
				return p;
			}
		}
		return null;
	}

	/** Any Skyrim world's mirror portal one of whose portal blocks this is (the blocks are there whichever world it is), or null. */
	public @Nullable Portal anyAt(int bx, int by, int bz) {
		for (Portal p : this.portals.values()) {
			if (p.inMirror() && p.contains(bx, by, bz)) {
				return p;
			}
		}
		return null;
	}

	/** Every mirror portal whose portal blocks or frame take in this block. */
	public List<Portal> touching(int bx, int by, int bz) {
		return this.touching(MIRROR, bx, by, bz);
	}

	/** Every portal of this dimension ({@link #MIRROR}: of any Skyrim world) whose portal blocks or frame take in this block. */
	public List<Portal> touching(String dimension, int bx, int by, int bz) {
		List<Portal> out = new ArrayList<>();
		for (Portal p : this.portals.values()) {
			if (p.dimension().equals(dimension) && p.frameContains(bx, by, bz)) {
				out.add(p);
			}
		}
		return out;
	}

	/** Every mirror portal (any Skyrim world) sharing a portal block with this rectangle. */
	public List<Portal> overlapping(int x, int y, int z, int axis, int width, int height) {
		return this.overlapping(MIRROR, x, y, z, axis, width, height);
	}

	/** Every portal of this dimension ({@link #MIRROR}: any Skyrim world) sharing a portal block with this rectangle. */
	public List<Portal> overlapping(String dimension, int x, int y, int z, int axis, int width, int height) {
		int maxX = axis == AXIS_X ? x + width - 1 : x;
		int maxZ = axis == AXIS_Z ? z + width - 1 : z;
		return this.within(dimension, x, y, z, maxX, y + height - 1, maxZ, 0);
	}

	/**
	 * Every portal of this dimension whose portal blocks, grown by {@code margin} blocks on every side,
	 * reach into the box (inclusive).
	 */
	public List<Portal> within(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int margin) {
		List<Portal> out = new ArrayList<>();
		for (Portal p : this.portals.values()) {
			if (p.dimension().equals(dimension) && p.x() - margin <= maxX && p.maxX() + margin >= minX && p.y() - margin <= maxY && p.maxY() + margin >= minY
				&& p.z() - margin <= maxZ && p.maxZ() + margin >= minZ) {
				out.add(p);
			}
		}
		return out;
	}

	/** A new, unbound mirror portal with the next id. */
	public Portal add(int world, int flags, int x, int y, int z, int axis, int width, int height, int side, String place) {
		Portal p = new Portal(this.next++, world, flags, x, y, z, axis, width, height, side < 0 ? -1 : 1, 0, clean(place));
		this.portals.put(p.id(), p);
		return p;
	}

	/** A new, unbound portal in another dimension with the next id. */
	public Portal addIn(String dimension, int x, int y, int z, int axis, int width, int height, int side, String place) {
		if (!validDimension(dimension)) {
			throw new IllegalArgumentException("not a dimension id: '" + dimension + "'");
		}
		Portal p = new Portal(this.next++, 0, 0, x, y, z, axis, width, height, side < 0 ? -1 : 1, 0, clean(place), dimension);
		this.portals.put(p.id(), p);
		return p;
	}

	/** Replaces the portal with this id (keeps the network's order). */
	public void put(Portal p) {
		this.portals.put(p.id(), p);
		this.next = Math.max(this.next, p.id() + 1);
	}

	/** Takes a portal out of the network; its partner (if any) is unbound. Returns the removed portal, or null. */
	public @Nullable Portal remove(int id) {
		Portal p = this.portals.remove(id);
		this.oneWay.remove(id);
		if (p != null && p.bound()) {
			Portal partner = this.portals.get(p.partner());
			if (partner != null && partner.partner() == id) {
				this.portals.put(partner.id(), partner.withPartner(0));
			}
		}
		return p;
	}

	/** Whether this portal leads anywhere by itself: bound to a partner, or open one way. */
	public boolean leads(Portal p) {
		return p.bound() || this.oneWay.containsKey(p.id());
	}

	/** Binds two portals that lead nowhere yet to each other, for good. */
	public Bind bind(int a, int b) {
		if (a == b) {
			return Bind.SAME;
		}
		Portal pa = this.portals.get(a), pb = this.portals.get(b);
		if (pa == null || pb == null) {
			return Bind.MISSING;
		}
		if (this.leads(pa) || this.leads(pb)) {
			return Bind.ALREADY_BOUND;
		}
		this.portals.put(a, pa.withPartner(b));
		this.portals.put(b, pb.withPartner(a));
		return Bind.BOUND;
	}

	/**
	 * Opens a portal that leads nowhere yet one way onto a dimension's arrival point (the Time-Wound's
	 * crack onto Home), for good. No partner portal: there is no way back through it.
	 */
	public Bind openOneWay(int id, String dimension) {
		Portal p = this.portals.get(id);
		if (p == null || !validDimension(dimension)) {
			return Bind.MISSING;
		}
		if (this.leads(p)) {
			return Bind.ALREADY_BOUND;
		}
		this.oneWay.put(id, dimension);
		return Bind.BOUND;
	}

	/** The dimension a one way portal opens onto, or null. */
	public @Nullable String oneWay(int id) {
		return this.oneWay.get(id);
	}

	/**
	 * Where stepping into this portal leads: its partner (the lock), or null when unbound. Use
	 * {@link #lead} for one way portals too.
	 */
	public @Nullable Portal destination(Portal from) {
		return from.bound() ? this.portals.get(from.partner()) : null;
	}

	/** Where stepping into this portal leads by itself: its partner, its one way arrival, or null. */
	public @Nullable Target lead(Portal from) {
		if (from.bound()) {
			return this.portals.containsKey(from.partner()) ? new Target.ToPortal(from.partner()) : null;
		}
		String dim = this.oneWay.get(from.id());
		return dim != null ? new Target.ToArrival(dim) : null;
	}

	// ---- arrivals, worlds visited, endings seen ----------------------------------------------------

	/** The arrival point recorded for a dimension (the Nether's is found once and kept), or null. */
	public @Nullable Arrival arrival(String dimension) {
		return this.arrivals.get(dimension);
	}

	public void setArrival(String dimension, Arrival a) {
		if (!validDimension(dimension)) {
			throw new IllegalArgumentException("not a dimension id: '" + dimension + "'");
		}
		this.arrivals.put(dimension, a);
	}

	/** Notes that the player has been to this dimension. True if that's new. */
	public boolean visit(String dimension) {
		return validDimension(dimension) && this.visited.add(dimension);
	}

	public boolean visited(String dimension) {
		return this.visited.contains(dimension);
	}

	/** Notes that this player has seen the ending (their first Seamstone). True if that's new. */
	public boolean seeEnding(UUID player) {
		return this.endings.add(player);
	}

	public boolean sawEnding(UUID player) {
		return this.endings.contains(player);
	}

	/** A dimension id as the store writes it: namespace:path, no spaces. */
	public static boolean validDimension(String dimension) {
		if (dimension == null) {
			return false;
		}
		int colon = dimension.indexOf(':');
		return colon > 0 && colon < dimension.length() - 1 && dimension.indexOf(':', colon + 1) < 0 && dimension.chars().allMatch(c -> c > ' ' && c < 127);
	}

	private static String clean(String place) {
		return place == null ? "" : place.replace('\n', ' ').replace('\r', ' ').strip();
	}

	public static String format(Portal p) {
		String where = p.inMirror() ? String.format(Locale.ROOT, "%08X", p.world()) : p.dimension();
		return String.format(Locale.ROOT, "%d %s %d %d %d %d %s %d %d %d %d %s", p.id(), where, p.flags(), p.x(), p.y(), p.z(), p.axis() == AXIS_X ? "X" : "Z",
			p.width(), p.height(), p.side(), p.partner(), clean(p.place())).stripTrailing();
	}

	public List<String> lines() {
		List<String> out = new ArrayList<>(this.portals.size() + 8);
		out.add(HEADER);
		out.add(HEADER_2);
		out.add("next " + this.next);
		for (Portal p : this.portals.values()) {
			out.add(format(p));
		}
		this.oneWay.forEach((id, dim) -> out.add("oneway " + id + " " + dim));
		this.arrivals.forEach((dim, a) -> out.add(String.format(Locale.ROOT, "arrival %s %.2f %.2f %.2f %.1f", dim, a.x(), a.y(), a.z(), a.yaw())));
		this.visited.forEach(dim -> out.add("visited " + dim));
		this.endings.forEach(uuid -> out.add("ending " + uuid));
		return out;
	}

	/**
	 * Reads lines written by {@link #lines}; bad ones go to {@code problems}. Later lines win. A bond whose
	 * other half is missing or bound elsewhere is dropped (reported), so every bond is mutual; so is a one
	 * way opening on a portal that's missing or bound.
	 */
	public static PortalStore parse(List<String> lines, List<String> problems) {
		PortalStore store = new PortalStore();
		int next = 1;
		int n = 0;
		Map<Integer, String> oneWay = new LinkedHashMap<>();
		for (String raw : lines) {
			n++;
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			if (line.startsWith("next ")) {
				try {
					next = Math.max(next, Integer.parseInt(line.substring(5).strip()));
				} catch (NumberFormatException e) {
					problems.add("line " + n + ": bad next id");
				}
				continue;
			}
			if (line.startsWith("oneway ") || line.startsWith("arrival ") || line.startsWith("visited ") || line.startsWith("ending ")) {
				parseExtra(store, oneWay, line, n, problems);
				continue;
			}
			String[] p = line.split("\\s+", 12);
			if (p.length < 11) {
				problems.add("line " + n + ": expected 'id world flags x y z axis width height side partner place'");
				continue;
			}
			try {
				int id = Integer.parseInt(p[0]);
				String dimension = MIRROR;
				int world = 0;
				if (p[1].indexOf(':') >= 0) {
					if (!validDimension(p[1])) {
						problems.add("line " + n + ": bad dimension id " + p[1]);
						continue;
					}
					dimension = p[1];
				} else {
					world = Integer.parseUnsignedInt(p[1], 16);
				}
				int flags = Integer.parseInt(p[2]);
				int x = Integer.parseInt(p[3]);
				int y = Integer.parseInt(p[4]);
				int z = Integer.parseInt(p[5]);
				int axis = switch (p[6].toUpperCase(Locale.ROOT)) {
					case "X" -> AXIS_X;
					case "Z" -> AXIS_Z;
					default -> -1;
				};
				int width = Integer.parseInt(p[7]);
				int height = Integer.parseInt(p[8]);
				int side = Integer.parseInt(p[9]);
				int partner = Integer.parseInt(p[10]);
				if (id < 1 || axis < 0 || width < 1 || width > MAX_SIZE || height < 1 || height > MAX_SIZE || (side != 1 && side != -1) || partner < 0
					|| flags < 0) {
					problems.add("line " + n + ": id, axis, size, side, partner or flags out of range");
					continue;
				}
				String place = p.length > 11 ? p[11].strip() : "";
				store.portals.put(id, new Portal(id, world, flags, x, y, z, axis, width, height, side, partner, place, dimension));
				next = Math.max(next, id + 1);
			} catch (NumberFormatException e) {
				problems.add("line " + n + ": " + e.getMessage());
			}
		}
		for (Portal p : List.copyOf(store.portals.values())) {
			if (!p.bound()) {
				continue;
			}
			Portal other = store.portals.get(p.partner());
			if (other == null || other.partner() != p.id() || other.id() == p.id()) {
				problems.add("portal " + p.id() + ": its bond to " + p.partner() + " isn't mutual; unbound");
				store.portals.put(p.id(), p.withPartner(0));
			}
		}
		oneWay.forEach((id, dim) -> {
			Portal p = store.portals.get(id);
			if (p == null || p.bound()) {
				problems.add("oneway " + id + ": " + (p == null ? "no such portal" : "the portal is bound to " + p.partner()) + "; dropped");
			} else {
				store.oneWay.put(id, dim);
			}
		});
		store.next = next;
		return store;
	}

	private static void parseExtra(PortalStore store, Map<Integer, String> oneWay, String line, int n, List<String> problems) {
		String[] p = line.split("\\s+");
		try {
			switch (p[0]) {
				case "oneway" -> {
					if (p.length != 3 || !validDimension(p[2])) {
						problems.add("line " + n + ": expected 'oneway <id> <dimension>'");
						return;
					}
					oneWay.put(Integer.parseInt(p[1]), p[2]);
				}
				case "arrival" -> {
					if (p.length != 6 || !validDimension(p[1])) {
						problems.add("line " + n + ": expected 'arrival <dimension> <x> <y> <z> <yaw>'");
						return;
					}
					Arrival a = new Arrival(Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4]), Float.parseFloat(p[5]));
					if (!Double.isFinite(a.x()) || !Double.isFinite(a.y()) || !Double.isFinite(a.z()) || !Float.isFinite(a.yaw())) {
						problems.add("line " + n + ": arrival out of range");
						return;
					}
					store.arrivals.put(p[1], a);
				}
				case "visited" -> {
					if (p.length != 2 || !validDimension(p[1])) {
						problems.add("line " + n + ": expected 'visited <dimension>'");
						return;
					}
					store.visited.add(p[1]);
				}
				default -> {
					if (p.length != 2) {
						problems.add("line " + n + ": expected 'ending <player uuid>'");
						return;
					}
					store.endings.add(UUID.fromString(p[1]));
				}
			}
		} catch (IllegalArgumentException e) {
			problems.add("line " + n + ": " + e.getMessage());
		}
	}
}
