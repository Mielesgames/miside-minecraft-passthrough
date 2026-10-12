package dev.skycraft.breach;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Every planned antechamber in a Minecraft world and what has happened to it, by Skyrim worldspace
 * and entrance door (pure; SkyBreach keeps it in the world's save folder as
 * {@code skycraft-breaches.txt}). One line per room:
 * {@code <world hex> <door hex> <bx> <floorY> <bz> <facing> <shell> <theme> <state> <name>}.
 */
public final class BreachStore {
	/** The room's blocks are in the world. */
	public static final int BUILT = 1;
	/** Its breach chest was put in (once per dungeon). */
	public static final int LOOT = 1 << 1;
	/** Someone has broken into it (its name was shown). */
	public static final int ENTERED = 1 << 2;

	public static final String HEADER = "# SkyCraft breach antechambers: world door bx floorY bz facing shell theme state name";

	public record Entry(BreachRoom room, int state, String name) {
		public boolean has(int flag) {
			return (this.state & flag) != 0;
		}

		public Entry with(int flag) {
			return new Entry(this.room, this.state | flag, this.name);
		}
	}

	private final Map<Long, Entry> entries = new LinkedHashMap<>();

	public @Nullable Entry get(int world, int doorRef) {
		return this.entries.get(BreachPlanner.key(world, doorRef));
	}

	public void put(Entry entry) {
		this.entries.put(entry.room().key(), entry);
	}

	public Collection<Entry> all() {
		return this.entries.values();
	}

	public int size() {
		return this.entries.size();
	}

	/** The room of this Skyrim world whose outer box holds the cell, or null. */
	public @Nullable Entry roomAt(int world, int x, int y, int z) {
		for (Entry e : this.entries.values()) {
			if (e.room().world() == world && e.room().contains(x, y, z)) {
				return e;
			}
		}
		return null;
	}

	/** Any world's room whose outer box holds the cell (the blocks are there whichever Skyrim world it is), or null. */
	public @Nullable Entry anyRoomAt(int x, int y, int z) {
		for (Entry e : this.entries.values()) {
			if (e.room().contains(x, y, z)) {
				return e;
			}
		}
		return null;
	}

	/** A room of this world that the new one would run into, or null. */
	public @Nullable Entry overlapping(BreachRoom room, int margin) {
		for (Entry e : this.entries.values()) {
			if (e.room().world() == room.world() && e.room().doorRef() != room.doorRef() && e.room().overlaps(room, margin)) {
				return e;
			}
		}
		return null;
	}

	public static String format(Entry e) {
		BreachRoom r = e.room();
		String name = e.name().replace('\n', ' ').replace('\r', ' ').strip();
		return String.format(Locale.ROOT, "%08X %08X %d %d %d %d %d %d %d %s", r.world(), r.doorRef(), r.bx(), r.floorY(), r.bz(), r.facing(), r.shell(), r.theme(), e.state(),
			name).stripTrailing();
	}

	public List<String> lines() {
		List<String> out = new ArrayList<>(this.entries.size() + 1);
		out.add(HEADER);
		for (Entry e : this.entries.values()) {
			out.add(format(e));
		}
		return out;
	}

	/** Reads lines written by {@link #lines}; bad ones go to {@code problems}. Later lines win. */
	public static BreachStore parse(List<String> lines, List<String> problems) {
		BreachStore store = new BreachStore();
		int n = 0;
		for (String raw : lines) {
			n++;
			String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}
			String[] p = line.split("\\s+", 10);
			if (p.length < 9) {
				problems.add("line " + n + ": expected 'world door bx floorY bz facing shell theme state name'");
				continue;
			}
			try {
				int world = Integer.parseUnsignedInt(p[0], 16);
				int door = Integer.parseUnsignedInt(p[1], 16);
				int bx = Integer.parseInt(p[2]);
				int floorY = Integer.parseInt(p[3]);
				int bz = Integer.parseInt(p[4]);
				int facing = Integer.parseInt(p[5]);
				int shell = Integer.parseInt(p[6]);
				int theme = Integer.parseInt(p[7]);
				int state = Integer.parseInt(p[8]);
				if (facing < 0 || facing > 3 || shell < 1 || shell > 2 || state < 0) {
					problems.add("line " + n + ": facing, shell or state out of range");
					continue;
				}
				String name = p.length > 9 ? p[9].strip() : "";
				store.put(new Entry(new BreachRoom(world, door, bx, floorY, bz, facing, shell, BreachPlanner.normalTheme(theme)), state, name));
			} catch (NumberFormatException e) {
				problems.add("line " + n + ": " + e.getMessage());
			}
		}
		return store;
	}
}
