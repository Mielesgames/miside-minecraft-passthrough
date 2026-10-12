package dev.skycraft.link;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Where each Skyrim world keeps its Minecraft blocks (pure, no Minecraft classes; SkySpace runs it).
 *
 * <p>Skyrim's worlds overlap: every interior cell sits near Skyrim's own (0, 0), and so does the middle
 * of Tamriel. The mirror is one Minecraft dimension, so each Skyrim world (each exterior worldspace and
 * each interior cell, as SkyState worldId names it) gets a space of its own in it: a square
 * {@link #SPACING} blocks across, centred on the space's origin. A Skyrim position p is at Minecraft
 * {@code p / 70 + origin} (y untouched). Tamriel's space is space 0, at the origin, so everything built
 * outside in Tamriel stays where it always was; the others are numbered in the order they are first
 * visited and laid out on a square spiral round it, so they stay as close to the origin as they can.
 *
 * <p>The table of which world has which space belongs to the Minecraft world (blocks live there): one
 * line per world, {@code <world hex> <space> <origin x> <origin z> <place>}, plus {@code migrated <n>}
 * once the blocks of a world made before spaces existed were sorted into them (SpaceMigration). Spaces
 * are never reused or moved. Thread safe.
 */
public final class WorldSpaces {
	/** Tamriel, Skyrim's main worldspace: space 0, at the origin. */
	public static final int TAMRIEL = 0x3C;
	/**
	 * Blocks between neighbouring spaces' origins. A world's things stay within half of it of its origin:
	 * Tamriel reaches about 3,800 blocks out, interiors a few hundred.
	 */
	public static final int SPACING = 1 << 15;
	public static final int HALF = SPACING / 2;
	/** {@link #SPACING} in 16-block sections. */
	public static final int SECTION_SPACING = SPACING / 16;
	/** The migration this table's world has had (SpaceMigration bumps it when it sorts more out). */
	public static final int MIGRATION = 1;

	public static final String HEADER = "# SkyCraft world spaces: where each Skyrim world (worldspace or interior cell) keeps its blocks.\n"
		+ "# Space n's origin is n's place on a square spiral round Tamriel's (space 0, at 0 0), " + SPACING + " blocks apart.\n"
		+ "# world space originX originZ place";

	private final ConcurrentHashMap<Integer, Integer> spaceOf = new ConcurrentHashMap<>();
	/** Space -> world, for telling which world a Minecraft position belongs to. */
	private final ConcurrentHashMap<Integer, Integer> worldOf = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Integer, String> places = new ConcurrentHashMap<>();
	/** Worlds in the order they got their space (guarded by this). */
	private final List<Integer> order = new ArrayList<>();
	private int next = 1;
	private volatile int migrated;
	private volatile int version;

	public WorldSpaces() {
		this.place(TAMRIEL, 0, "Tamriel");
	}

	// ---- geometry ----------------------------------------------------------------------------------

	/** Which ring of the spiral space {@code n} is on (0 for space 0). */
	static int ring(int n) {
		int k = (int) Math.ceil((Math.sqrt(n + 1.0) - 1.0) / 2.0);
		while ((2L * k + 1) * (2L * k + 1) <= n) {
			k++;
		}
		while (k > 0 && (2L * k - 1) * (2L * k - 1) > n) {
			k--;
		}
		return k;
	}

	/**
	 * Space {@code n}'s grid cell {x, z}: space 0 at (0, 0), 1 at (1, 0), 2 at (1, 1), 3 at (0, 1), ...
	 * round and round, each ring a square one further out.
	 */
	public static int[] cell(int n) {
		long c = packedCell(n);
		return new int[] { (int) (c >> 32), (int) c };
	}

	/** {@link #cell} as x in the high and z in the low 32 bits (no allocation). */
	private static long packedCell(int n) {
		if (n <= 0) {
			return 0L;
		}
		int k = ring(n);
		int side = 2 * k;
		int i = n - (2 * k - 1) * (2 * k - 1);
		int x, z;
		if (i < side) {
			x = k;
			z = -k + 1 + i;
		} else if ((i -= side) < side) {
			x = k - 1 - i;
			z = k;
		} else if ((i -= side) < side) {
			x = -k;
			z = k - 1 - i;
		} else {
			i -= side;
			x = -k + 1 + i;
			z = -k;
		}
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	/** The space number at grid cell (x, z) (the inverse of {@link #cell}). */
	public static int spaceAtCell(int x, int z) {
		int k = Math.max(Math.abs(x), Math.abs(z));
		if (k == 0) {
			return 0;
		}
		int side = 2 * k;
		int first = (2 * k - 1) * (2 * k - 1);
		if (x == k && z > -k) {
			return first + (z + k - 1);
		}
		if (z == k) {
			return first + side + (k - 1 - x);
		}
		if (x == -k) {
			return first + 2 * side + (k - 1 - z);
		}
		return first + 3 * side + (x + k - 1);
	}

	/** Minecraft x of a space's origin (space 0, and -1 for "not known yet", at 0). */
	public static int originX(int space) {
		return (int) (packedCell(space) >> 32) * SPACING;
	}

	/** Minecraft z of a space's origin. */
	public static int originZ(int space) {
		return (int) packedCell(space) * SPACING;
	}

	/** The grid cell (along one axis) a Minecraft coordinate is in. */
	public static int cellOf(double v) {
		return (int) Math.floor((v + HALF) / SPACING);
	}

	public static int cellOf(int v) {
		return Math.floorDiv(v + HALF, SPACING);
	}

	/** The grid cell (along one axis) a section coordinate is in. */
	public static int cellOfSection(int s) {
		return Math.floorDiv(s + HALF / 16, SECTION_SPACING);
	}

	/** The space a Minecraft position is in (whether or not any world has it). */
	public static int spaceAt(double x, double z) {
		return spaceAtCell(cellOf(x), cellOf(z));
	}

	/** A Minecraft coordinate in its own space's terms (what Skyrim sees: its position / 70). */
	public static double local(double v) {
		return v - (double) cellOf(v) * SPACING;
	}

	public static int local(int v) {
		return v - cellOf(v) * SPACING;
	}

	public static int localSection(int s) {
		return s - cellOfSection(s) * SECTION_SPACING;
	}

	// ---- the table ---------------------------------------------------------------------------------

	/** The world's space, or -1 if it has none yet. */
	public int spaceOf(int world) {
		Integer s = this.spaceOf.get(world);
		return s != null ? s : -1;
	}

	/** The world whose space this is, or 0 if none. */
	public int worldOf(int space) {
		Integer w = this.worldOf.get(space);
		return w != null ? w : 0;
	}

	/** The world a Minecraft position belongs to, or 0 if its space is nobody's. */
	public int worldAt(double x, double z) {
		return this.worldOf(spaceAt(x, z));
	}

	/** Bumps whenever a world gets a space (or the table is replaced): cached origins are stale. */
	public int version() {
		return this.version;
	}

	public int size() {
		return this.spaceOf.size();
	}

	/** Every world and its space, in the order they got them. */
	public synchronized Map<Integer, Integer> all() {
		Map<Integer, Integer> out = new LinkedHashMap<>();
		for (Integer world : this.order) {
			out.put(world, this.spaceOf.get(world));
		}
		return out;
	}

	/** Skyrim's name for where the world is ("" if not known). */
	public String place(int world) {
		String p = this.places.get(world);
		return p != null ? p : "";
	}

	/** Whether the world has a space and a name for it. */
	public boolean named(int world) {
		return !this.place(world).isEmpty();
	}

	/**
	 * The world's space, given it now (the next free one) if it has none. {@code place} names it in the
	 * file (Skyrim's name for where it is), kept from the first time it's known. Returns whether the
	 * table changed (and should be saved) in {@code changed[0]}.
	 */
	public synchronized int allocate(int world, @Nullable String place, boolean @Nullable [] changed) {
		Integer have = this.spaceOf.get(world);
		String name = place == null ? "" : clean(place);
		if (have != null) {
			if (!name.isEmpty() && this.places.getOrDefault(world, "").isEmpty()) {
				this.places.put(world, name);
				if (changed != null) {
					changed[0] = true;
				}
			}
			return have;
		}
		int space = this.next++;
		this.place(world, space, name);
		if (changed != null) {
			changed[0] = true;
		}
		return space;
	}

	/** Takes another table's spaces (a guest's copy of the host's). Returns true if anything was new. */
	public synchronized boolean merge(Map<Integer, Integer> spaces) {
		boolean any = false;
		for (Map.Entry<Integer, Integer> e : spaces.entrySet()) {
			int world = e.getKey(), space = e.getValue();
			if (space < 0 || this.spaceOf.containsKey(world) || this.worldOf.containsKey(space)) {
				continue;
			}
			this.place(world, space, "");
			this.next = Math.max(this.next, space + 1);
			any = true;
		}
		return any;
	}

	private void place(int world, int space, String name) {
		this.spaceOf.put(world, space);
		this.worldOf.put(space, world);
		this.places.put(world, name);
		this.order.add(world);
		this.version++;
	}

	public int migrated() {
		return this.migrated;
	}

	public void setMigrated(int migrated) {
		this.migrated = migrated;
	}

	private static String clean(String s) {
		return s.replace('\n', ' ').replace('\r', ' ').strip();
	}

	public synchronized List<String> lines() {
		List<String> out = new ArrayList<>(this.order.size() + 4);
		out.addAll(List.of(HEADER.split("\n")));
		if (this.migrated > 0) {
			out.add("migrated " + this.migrated);
		}
		for (int world : this.order) {
			int space = this.spaceOf.get(world);
			out.add(String.format(Locale.ROOT, "%08X %d %d %d %s", world, space, originX(space), originZ(space), this.place(world)).stripTrailing());
		}
		return out;
	}

	/**
	 * Reads lines written by {@link #lines}; bad ones go to {@code problems}. Tamriel is always space 0,
	 * and no two worlds share a space (the first line wins).
	 */
	public static WorldSpaces parse(List<String> lines, List<String> problems) {
		WorldSpaces t = new WorldSpaces();
		int n = 0;
		for (String raw : lines) {
			n++;
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] p = line.split("\\s+", 5);
			try {
				if (p[0].equals("migrated")) {
					t.migrated = p.length > 1 ? Integer.parseInt(p[1]) : 0;
					continue;
				}
				if (p.length < 2) {
					problems.add("line " + n + ": expected 'world space originX originZ place'");
					continue;
				}
				int world = Integer.parseUnsignedInt(p[0], 16);
				int space = Integer.parseInt(p[1]);
				String place = p.length > 4 ? clean(p[4]) : "";
				if (world == TAMRIEL) {
					if (space != 0) {
						problems.add("line " + n + ": Tamriel is always space 0");
					}
					if (!place.isEmpty()) {
						t.places.put(TAMRIEL, place);
					}
					continue;
				}
				if (space <= 0) {
					problems.add("line " + n + ": space " + space + " is not a world's (space 0 is Tamriel's)");
					continue;
				}
				if (t.spaceOf.containsKey(world) || t.worldOf.containsKey(space)) {
					problems.add("line " + n + ": world " + p[0] + " or space " + space + " is listed twice; kept the first");
					continue;
				}
				t.place(world, space, place);
				t.next = Math.max(t.next, space + 1);
			} catch (NumberFormatException e) {
				problems.add("line " + n + ": " + e.getMessage());
			}
		}
		return t;
	}
}
